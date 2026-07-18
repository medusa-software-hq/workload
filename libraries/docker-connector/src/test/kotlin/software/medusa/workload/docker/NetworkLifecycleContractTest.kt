package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Contract tests for the M4-B2 networking surface, against a **real** Docker daemon. Same
 * self-skip-locally / required-in-CI policy as the other contract suites.
 *
 * Proves the primitives `workload run` needs to host the metadata emulator on a per-run bridge:
 * create a labeled bridge, read its gateway IP, attach a container to it with an `--add-host`
 * entry, confirm from *inside* the container that the alias resolved and the inspect-reported
 * gateway is the container's actual default route, look the container's own IP up, and remove the
 * network.
 *
 * Note on `host-gateway`: the daemon resolves it to the *default* bridge's gateway, which need not
 * equal a custom network's gateway — so this asserts the alias was written, not that it equals the
 * run network's gateway. That's why B3 leads with the `GCE_METADATA_HOST=<gateway>:<port>` pointer
 * and treats the DNS alias as a documented best-effort fallback.
 */
class NetworkLifecycleContractTest {

  private val runToken = "nettest-" + java.util.UUID.randomUUID().toString().take(8)
  private val ownerLabel = "ms-workload.test-owner"

  private fun withNetworking(block: suspend (DockerConnector) -> Unit) {
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
      runBlocking { connector.images.pull(BUSYBOX).collect {} }
      try {
        runBlocking { block(connector) }
      } finally {
        runBlocking { reap(connector) }
      }
    }
  }

  private fun labels(extra: Map<String, String> = emptyMap()) =
      mapOf(ownerLabel to runToken) + extra

  private suspend fun reap(connector: DockerConnector) {
    for (c in connector.containers.list(labels = mapOf(ownerLabel to runToken))) {
      runCatching { connector.containers.remove(c.id, force = true) }
    }
    for (n in connector.networks.list(labels = mapOf(ownerLabel to runToken))) {
      runCatching { connector.networks.remove(n.id) }
    }
  }

  private fun List<LogFrame>.textOf(stream: LogStream): String =
      filter { it.stream == stream }.joinToString("") { it.bytes.toString(Charsets.UTF_8) }

  @Test
  fun `create, inspect for gateway, label-filtered list, and remove`() =
      withNetworking { connector ->
        val name = "ms-workload-$runToken"
        val created = connector.networks.create(name = name, labels = labels())

        val inspect = connector.networks.inspect(created.id)
        assertEquals(name, inspect.name)
        assertEquals("bridge", inspect.driver)
        assertNotNull(inspect.gateway, "a bridge network should have an IPAM gateway")

        val listed = connector.networks.list(labels = mapOf(ownerLabel to runToken))
        assertTrue(listed.any { it.id == created.id }, "label filter should find our network")

        connector.networks.remove(created.id)
        val after = connector.networks.list(labels = mapOf(ownerLabel to runToken))
        assertTrue(after.none { it.id == created.id }, "removed network should be gone")
      }

  @Test
  fun `a container on the network sees the add-host alias and routes via the inspect gateway`() =
      withNetworking { connector ->
        val name = "ms-workload-$runToken-run"
        val network = connector.networks.create(name = name, labels = labels())
        val gateway = connector.networks.inspect(network.id).gateway
        assertNotNull(gateway, "bridge gateway expected")

        // Print /etc/hosts (where --add-host lands) and the routing table, then linger briefly so
        // the container is still up when we inspect its IP.
        val created =
            connector.containers.create(
                image = BUSYBOX,
                cmd = listOf("sh", "-c", "cat /etc/hosts; echo ---ROUTE---; ip route; sleep 3"),
                labels = labels(),
                autoRemove = false,
                networkMode = name,
                extraHosts = listOf("metadata.google.internal:host-gateway"),
            )
        connector.containers.start(created.id)

        // Container-IP lookup (what B3's peer-check uses) — read while it's still running.
        val containerIp = connector.containers.inspect(created.id).ipOnNetwork(name)
        assertNotNull(containerIp, "the container should have an IP on its run network")

        val text = connector.logs.logs(created.id, follow = true).toList().textOf(LogStream.STDOUT)
        connector.containers.wait(created.id)

        val hostsLine = text.lineSequence().firstOrNull { it.contains("metadata.google.internal") }
        assertNotNull(hostsLine, "--add-host should have written metadata.google.internal to hosts")

        val defaultRoute = text.lineSequence().firstOrNull { it.trimStart().startsWith("default") }
        assertNotNull(defaultRoute, "container should have a default route")
        val containerGateway = defaultRoute.substringAfter("via ").trim().substringBefore(' ')
        assertEquals(
            gateway,
            containerGateway,
            "the network's inspect gateway must be the container's actual default route",
        )

        connector.containers.remove(created.id, force = true)
        connector.networks.remove(network.id)
      }

  private companion object {
    const val BUSYBOX = "busybox:latest"
  }
}
