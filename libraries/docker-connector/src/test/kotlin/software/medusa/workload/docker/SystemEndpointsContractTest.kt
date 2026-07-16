package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Contract tests against a **real** Docker daemon (the M3-01 acceptance surface). They self-skip
 * when no daemon is reachable, so the suite stays green on dev machines without Docker. Point them
 * at a specific daemon with `DOCKER_HOST=unix://<path>` (e.g. a socket forwarded from the Ubuntu
 * VM); default is `/var/run/docker.sock`.
 *
 * **CI must not silently skip.** When `DOCKER_CONTRACT_REQUIRED` is set (CI does), an unreachable
 * daemon is a hard failure instead of a skip — otherwise a runner that lost its Docker daemon would
 * give a false green, defeating the point of testing our Docker code in CI.
 */
class SystemEndpointsContractTest {

  private fun withDaemon(block: suspend (DockerConnector) -> Unit) {
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
      runBlocking { block(connector) }
    }
  }

  @Test
  fun `ping returns the daemon's advertised API version`() = withDaemon { connector ->
    val ping = connector.ping()
    assertTrue(ping.apiVersion.isNotBlank(), "ping should report an API version")
    assertTrue(
        ApiVersion.compare(ping.apiVersion, ApiVersion.MIN_SUPPORTED) >= 0,
        "daemon API ${ping.apiVersion} should be >= our floor ${ApiVersion.MIN_SUPPORTED}",
    )
  }

  @Test
  fun `version returns a self-consistent daemon version`() = withDaemon { connector ->
    val version = connector.version()
    assertTrue(version.version.isNotBlank())
    assertTrue(version.apiVersion.isNotBlank())
  }

  @Test
  fun `info returns system information over the negotiated API version`() =
      withDaemon { connector ->
        val info = connector.info()
        // Every daemon reports a server version and a non-negative container count.
        assertTrue(info.serverVersion?.isNotBlank() ?: false, "info should report a server version")
        assertTrue(info.containers >= 0)
      }

  @Test
  fun `ping version and info all round-trip against the same daemon`() = withDaemon { connector ->
    val ping = connector.ping()
    val version = connector.version()
    val info = connector.info()
    // The daemon's ping API-version and /version ApiVersion agree.
    assertTrue(ping.apiVersion == version.apiVersion)
    assertTrue(info.serverVersion == version.version || info.serverVersion != null)
  }

  @Test
  fun `Netty transport init cost is measured and recorded`() = withDaemon { connector ->
    // withDaemon already did one ping (the reachability probe) on this connector, so the transport
    // is warm here; spin up a *fresh* connector to time a genuine cold init.
    DockerConnector(DockerConnectorConfig.fromEnvironment()).use { cold ->
      val coldStart = System.nanoTime()
      runBlocking { cold.ping() }
      val coldMs = (System.nanoTime() - coldStart) / 1_000_000.0

      val warmStart = System.nanoTime()
      runBlocking { cold.ping() }
      val warmMs = (System.nanoTime() - warmStart) / 1_000_000.0

      // Recorded to the test log (see the story close-out note in the design/findings).
      println(
          "[docker-connector] Netty+Armeria cold init: %.1f ms (first ping), warm ping: %.1f ms, init overhead ~%.1f ms"
              .format(coldMs, warmMs, coldMs - warmMs)
      )
      assertTrue(coldMs >= warmMs, "cold init should be no faster than a warm call")
    }
  }
}
