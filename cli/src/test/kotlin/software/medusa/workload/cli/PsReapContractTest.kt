package software.medusa.workload.cli

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig

/**
 * The orphaned-container gap and its mitigation, against a real daemon.
 *
 * `workload run` accepts that a hard kill of the CLI leaves its container running — there's no
 * heartbeat, so nothing can reap it automatically. `workload ps` is how you see those, and `--reap`
 * is how you clear them. This test creates exactly that situation: a labelled container that no CLI
 * is watching, i.e. what a SIGKILLed `workload run` leaves behind.
 *
 * Same policy as the other contract suites: self-skip without a daemon, hard failure under
 * `DOCKER_CONTRACT_REQUIRED`.
 */
class PsReapContractTest {

  private val runToken = "ps-" + java.util.UUID.randomUUID().toString().take(8)

  private fun withConnector(block: suspend (DockerConnector) -> Unit) {
    DockerConnector(DockerConnectorConfig.fromEnvironment()).use { connector ->
      val reachable =
          try {
            runBlocking { connector.ping() }
            true
          } catch (e: DockerConnectionException) {
            false
          }
      if (!reachable) {
        if (!System.getenv("DOCKER_CONTRACT_REQUIRED").isNullOrBlank()) {
          fail("DOCKER_CONTRACT_REQUIRED is set but no Docker daemon was reachable.")
        }
        assumeTrue(false, "no reachable Docker daemon; skipping contract test")
      }
      runBlocking { connector.images.pull(BUSYBOX).collect {} }
      try {
        runBlocking { block(connector) }
      } finally {
        runBlocking {
          for (c in connector.containers.list(labels = mapOf(workloadProfileLabel to runToken))) {
            runCatching { connector.containers.remove(c.id, force = true) }
          }
          for (n in connector.networks.list(labels = mapOf(workloadNetworkLabel to runToken))) {
            runCatching { connector.networks.remove(n.id) }
          }
        }
      }
    }
  }

  /**
   * Exactly what `workload run` leaves behind when the CLI is killed: a labelled, running
   * container.
   */
  private suspend fun orphan(connector: DockerConnector, cmd: List<String>): String {
    val created =
        connector.containers.create(
            image = BUSYBOX,
            cmd = cmd,
            labels =
                mapOf(
                    workloadProfileLabel to runToken,
                    workloadRevisionLabel to "1",
                    workloadWorkerLabel to "test-worker",
                ),
            autoRemove = false,
        )
    connector.containers.start(created.id)
    return created.id
  }

  @Test
  fun `ps finds a container orphaned by a hard kill, by label alone`() =
      withConnector { connector ->
        val id = orphan(connector, listOf("sh", "-c", "sleep 60"))

        // The label-key filter is what `workload ps` uses: "workload owns this", whatever the
        // profile.
        val listed = connector.containers.list(labelKeys = listOf(workloadProfileLabel))
        val mine = listed.singleOrNull { it.id == id }

        assertTrue(mine != null, "the orphan should be listed by its ms-workload.profile label")
        assertTrue(
            mine!!.running,
            "a hard-killed run leaves the container *running* — that's the gap",
        )

        val row = toPsRow(mine, Instant.now())
        assertEquals(runToken, row.profile)
        assertEquals("1", row.revision)
        assertEquals("running", row.state)
      }

  @Test
  fun `reaping stops and removes a running orphan`() = withConnector { connector ->
    val id = orphan(connector, listOf("sh", "-c", "sleep 60"))

    // What --reap does: stop (with grace) then remove.
    connector.containers.stop(id, 5.seconds)
    connector.containers.remove(id, force = true)

    val remaining = connector.containers.list(labelKeys = listOf(workloadProfileLabel))
    assertTrue(remaining.none { it.id == id }, "the orphan should be gone after a reap")
  }

  @Test
  fun `reaping removes an exited leftover too`() = withConnector { connector ->
    val id = orphan(connector, listOf("sh", "-c", "exit 0"))
    connector.containers.wait(id)

    val listed = connector.containers.list(labelKeys = listOf(workloadProfileLabel))
    val mine = listed.single { it.id == id }
    assertTrue(!mine.running, "it has exited")
    // Exited containers need no stop — remove alone clears them.
    connector.containers.remove(id, force = true)

    assertTrue(
        connector.containers.list(labelKeys = listOf(workloadProfileLabel)).none { it.id == id }
    )
  }

  @Test
  fun `reaping removes a labeled network orphaned by a hard kill`() = withConnector { connector ->
    // What a SIGKILLed `workload run` can now also leave behind: its per-run bridge network.
    val net =
        connector.networks.create(
            name = "ms-workload-net-$runToken",
            labels = mapOf(workloadNetworkLabel to runToken),
        )

    // `workload ps --reap` finds workload networks by the label key alone, like it does containers.
    val listed = connector.networks.list(labelKeys = listOf(workloadNetworkLabel))
    assertTrue(listed.any { it.id == net.id }, "the orphaned network should be listed by its label")

    connector.networks.remove(net.id)
    val after = connector.networks.list(labelKeys = listOf(workloadNetworkLabel))
    assertTrue(after.none { it.id == net.id }, "the network should be gone after a reap")
  }

  @Test
  fun `ps ignores containers workload does not own`() = withConnector { connector ->
    // A container with no ms-workload labels must never appear — reaping someone else's container
    // would be unforgivable.
    val foreign =
        connector.containers.create(
            image = BUSYBOX,
            cmd = listOf("sh", "-c", "sleep 30"),
            labels = mapOf("something.else" to "true"),
            autoRemove = false,
        )
    try {
      connector.containers.start(foreign.id)
      val listed = connector.containers.list(labelKeys = listOf(workloadProfileLabel))
      assertTrue(listed.none { it.id == foreign.id }, "an unlabelled container must not be listed")
    } finally {
      runCatching { connector.containers.remove(foreign.id, force = true) }
    }
  }

  private companion object {
    const val BUSYBOX = "busybox:latest"
  }
}
