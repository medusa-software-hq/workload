package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Contract tests for the M3-02 container lifecycle, run against a **real** Docker daemon. Same
 * self-skip-locally / required-in-CI policy as [SystemEndpointsContractTest]: without a reachable
 * daemon they skip, unless `DOCKER_CONTRACT_REQUIRED` is set (CI), where an unreachable daemon is a
 * hard failure.
 *
 * All tests use `busybox`; the harness best-effort `docker pull`s it once so a fresh runner isn't
 * missing the image. Every container is labeled with a per-run token and force-removed in a
 * finally, so a failing test can't leak containers into later runs.
 */
class ContainerLifecycleContractTest {

  private val runToken = "test-" + java.util.UUID.randomUUID().toString().take(8)
  private val ownerLabel = "ms-workload.test-owner"

  private fun withContainers(block: suspend (ContainerApi) -> Unit) {
    val config = DockerConnectorConfig.fromEnvironment()
    DockerConnector(config).use { connector ->
      val reachable =
          try {
            runBlocking { connector.ping() }
            true
          } catch (e: DockerConnectionException) {
            false
          }
      if (!reachable) {
        if (!System.getenv("DOCKER_CONTRACT_REQUIRED").isNullOrBlank()) {
          fail(
              "DOCKER_CONTRACT_REQUIRED is set but no Docker daemon was reachable at " +
                  "${config.socketPath}; the Docker contract tests must run here, not skip."
          )
        }
        assumeTrue(false, "no reachable Docker daemon; skipping contract test")
      }
      ensureBusybox()
      try {
        runBlocking { block(connector.containers) }
      } finally {
        runBlocking { reapRunContainers(connector.containers) }
      }
    }
  }

  private fun labels(extra: Map<String, String> = emptyMap()): Map<String, String> =
      mapOf(ownerLabel to runToken) + extra

  private suspend fun reapRunContainers(containers: ContainerApi) {
    for (summary in containers.list(labels = mapOf(ownerLabel to runToken))) {
      runCatching { containers.remove(summary.id, force = true) }
    }
  }

  @Test
  fun `create start wait round-trips the environment and reports exit 0`() =
      withContainers { containers ->
        // The container asserts FOO=bar came through the env (body-only, never argv) and exits 0.
        val created =
            containers.create(
                image = BUSYBOX,
                cmd = listOf("sh", "-c", ENV_ASSERT),
                env = listOf("FOO=bar"),
                labels = labels(),
                autoRemove = false,
            )
        assertTrue(created.id.isNotBlank())
        containers.start(created.id)
        val result = containers.wait(created.id)
        assertEquals(0, result.statusCode, "env-matching container should exit 0")
      }

  @Test
  fun `a wrong env value makes the same assertion fail nonzero`() = withContainers { containers ->
    val created =
        containers.create(
            image = BUSYBOX,
            cmd = listOf("sh", "-c", ENV_ASSERT),
            env = listOf("FOO=not-bar"),
            labels = labels(),
            autoRemove = false,
        )
    containers.start(created.id)
    val result = containers.wait(created.id)
    assertEquals(1, result.statusCode, "mismatched env should make the test command exit 1")
  }

  @Test
  fun `exit code is reported faithfully`() = withContainers { containers ->
    val created =
        containers.create(
            image = BUSYBOX,
            cmd = listOf("sh", "-c", "exit 3"),
            labels = labels(),
            autoRemove = false,
        )
    containers.start(created.id)
    assertEquals(3, containers.wait(created.id).statusCode)
  }

  @Test
  fun `stop SIGKILLs a SIGTERM-ignoring container after the grace period, exit 137`() =
      withContainers { containers ->
        // Traps and ignores TERM, so only the post-grace SIGKILL can stop it.
        val created =
            containers.create(
                image = BUSYBOX,
                cmd = listOf("sh", "-c", "trap '' TERM; while true; do sleep 1; done"),
                labels = labels(),
                autoRemove = false,
            )
        containers.start(created.id)
        val start = System.nanoTime()
        containers.stop(created.id, timeout = 2.seconds)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0

        // Grace was ~2 s: the SIGKILL shouldn't fire much before, and the whole thing well under
        // 30.
        assertTrue(elapsedMs >= 1_500, "stop returned in ${elapsedMs}ms — grace period was skipped")
        assertTrue(elapsedMs < 30_000, "stop took ${elapsedMs}ms — SIGKILL backstop didn't fire")
        assertEquals(137, containers.wait(created.id).statusCode, "SIGKILL exit should be 137")
      }

  @Test
  fun `AutoRemove reaps the container on exit`() = withContainers { containers ->
    val created =
        containers.create(
            image = BUSYBOX,
            cmd = listOf("sh", "-c", "exit 0"),
            labels = labels(mapOf("ms-workload.case" to "autoremove-on")),
            autoRemove = true,
        )
    containers.start(created.id)
    containers.wait(created.id)
    // Give the daemon a beat to complete async removal, then confirm it's gone.
    val gone =
        (1..50).any {
          Thread.sleep(100)
          containers.list(labels = mapOf("ms-workload.case" to "autoremove-on")).isEmpty()
        }
    assertTrue(gone, "AutoRemove container should be removed shortly after exit")
  }

  @Test
  fun `without AutoRemove the container persists and is inspectable, then removable`() =
      withContainers { containers ->
        val created =
            containers.create(
                image = BUSYBOX,
                cmd = listOf("sh", "-c", "exit 0"),
                labels = labels(mapOf("ms-workload.case" to "autoremove-off")),
                autoRemove = false,
            )
        containers.start(created.id)
        containers.wait(created.id)

        val inspect = containers.inspect(created.id)
        assertEquals(created.id, inspect.id)
        assertFalse(inspect.state.running, "container should have exited")
        assertEquals(runToken, inspect.config.labels[ownerLabel])

        containers.remove(created.id, force = false)
        val listedAfter = containers.list(labels = mapOf("ms-workload.case" to "autoremove-off"))
        assertTrue(listedAfter.none { it.id == created.id }, "removed container should be gone")
      }

  @Test
  fun `list filters by label`() = withContainers { containers ->
    val marker = "ms-workload.case"
    val a =
        containers.create(
            image = BUSYBOX,
            cmd = listOf("sh", "-c", "exit 0"),
            labels = labels(mapOf(marker to "list-a")),
            autoRemove = false,
        )
    val b =
        containers.create(
            image = BUSYBOX,
            cmd = listOf("sh", "-c", "exit 0"),
            labels = labels(mapOf(marker to "list-b")),
            autoRemove = false,
        )
    val onlyA = containers.list(labels = mapOf(marker to "list-a"))
    assertTrue(onlyA.any { it.id == a.id }, "filtered list should contain the matching container")
    assertTrue(onlyA.none { it.id == b.id }, "filtered list should exclude non-matching containers")
  }

  private fun ensureBusybox() {
    // Best-effort: pull busybox so a fresh runner isn't missing the image. Image pull is a later M3
    // story; until then the test harness leans on the CLI just to prime the cache.
    runCatching {
          ProcessBuilder("docker", "pull", BUSYBOX)
              .redirectErrorStream(true)
              .start()
              .also { it.inputStream.readBytes() }
              .waitFor()
        }
        .getOrNull()
  }

  private companion object {
    const val BUSYBOX = "busybox:latest"
    // Shell test asserting the env var FOO arrived as "bar": exits 0 on match, 1 otherwise.
    // `${'$'}` is a literal dollar so Kotlin doesn't try to interpolate `$FOO`.
    const val ENV_ASSERT = "[ \"${'$'}FOO\" = bar ]"
  }
}
