package software.medusa.workload.runtime

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig

/**
 * The acceptance test for the cloudflared-access sidecar's Docker wiring: against a real daemon, a
 * workload container reaches the sidecar over the network [startMetadataSidecar] created for the
 * run, and the network survives [stopCloudflaredAccessSidecar] since (unlike [stopMetadataSidecar])
 * it doesn't own the network — only removing the sidecar's own container.
 *
 * Stands in for the real `images/cloudflared-access` image with a `busybox httpd`, same as
 * [MetadataSidecarIsolationContractTest] — this suite is about the network wiring
 * [startCloudflaredAccessSidecar]/[stopCloudflaredAccessSidecar] set up, not `cloudflared`'s own
 * tunnel handling.
 *
 * Same self-skip-locally / required-in-CI policy as the other contract suites.
 */
class CloudflaredAccessSidecarContractTest {

  private val runToken = "cfaiso-" + java.util.UUID.randomUUID().toString().take(8)
  private val ownerLabel = "ms-workload.test-owner"
  private val labels = mapOf(ownerLabel to runToken)

  private fun withConnector(block: suspend (DockerConnector) -> Unit) {
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

  private suspend fun reap(connector: DockerConnector) {
    for (c in connector.containers.list(labels = labels)) {
      runCatching { connector.containers.remove(c.id, force = true) }
    }
    for (n in connector.networks.list(labels = labels)) {
      runCatching { connector.networks.remove(n.id) }
    }
  }

  /** A busybox `httpd` standing in for the real `cloudflared` image — see the class doc. */
  private fun fakeSidecarCmd(port: Int) =
      listOf(
          "sh",
          "-c",
          "mkdir -p /www && echo -n 'cloudflared-response' > /www/index.html && " +
              "httpd -f -p $port -h /www",
      )

  private suspend fun fetchFrom(
      connector: DockerConnector,
      network: String,
      address: String,
  ): String? {
    val out = StringBuilder()
    val exit =
        runContainerToCompletion(
            connector = connector,
            image = BUSYBOX,
            env = emptyList(),
            labels = labels,
            networkMode = network,
            cmd = listOf("sh", "-c", "wget -T 3 -q -O - http://$address/"),
            onStdout = { out.append(String(it)) },
            onStderr = {},
        )
    return if (exit == 0) out.toString() else null
  }

  @Test
  fun `a workload container reaches the cloudflared sidecar over the run's shared network`() =
      withConnector { connector ->
        val networkName = "ms-workload-$runToken"
        val metadataSidecar =
            startMetadataSidecar(
                connector = connector,
                image = BUSYBOX,
                networkName = networkName,
                env = emptyList(),
                labels = labels,
                cmd = listOf("sh", "-c", "mkdir -p /www && httpd -f -p 8080 -h /www"),
            )
        try {
          val cloudflaredSidecar =
              startCloudflaredAccessSidecar(
                  connector = connector,
                  image = BUSYBOX,
                  networkName = networkName,
                  env = emptyList(),
                  labels = labels,
                  port = SIDECAR_PORT,
                  cmd = fakeSidecarCmd(SIDECAR_PORT),
              )
          try {
            val response = fetchFrom(connector, networkName, cloudflaredSidecar.address)
            assertNotNull(response, "the workload container should reach the cloudflared sidecar")
            assertTrue("cloudflared-response" in response)
          } finally {
            // Removing only the cloudflared sidecar's own container must leave the shared network
            // (and the metadata sidecar still attached to it) intact.
            stopCloudflaredAccessSidecar(connector, cloudflaredSidecar)
          }
          val networkStillUp = connector.networks.inspect(metadataSidecar.networkId)
          assertNotNull(
              networkStillUp,
              "the shared network should survive stopCloudflaredAccessSidecar",
          )
        } finally {
          stopMetadataSidecar(connector, metadataSidecar)
        }
      }

  private companion object {
    const val BUSYBOX = "busybox:latest"
    const val SIDECAR_PORT = 8081
  }
}
