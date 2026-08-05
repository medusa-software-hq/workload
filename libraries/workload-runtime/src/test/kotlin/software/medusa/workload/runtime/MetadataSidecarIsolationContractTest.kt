package software.medusa.workload.runtime

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig

/**
 * The acceptance test for the metadata-emulator **sidecar**: against a real Docker daemon, a
 * workload container reaches its own tenant's sidecar over their shared per-run network, and a
 * workload container on a *different* tenant's network cannot reach the first tenant's sidecar at
 * all. Not "gets a 403" — no route exists, because [startMetadataSidecar] gives every run its own
 * bridge network and Docker never forwards between two disjoint user-defined bridge networks. That
 * structural guarantee, not a peer-IP check, is what a shared-host emulator could never offer (see
 * `MetadataEmulator.kt`'s and `MetadataSidecar.kt`'s doc comments).
 *
 * Stands in for the real `images/metadata-emulator` JVM image with a `busybox httpd` — this suite
 * is about the network wiring [startMetadataSidecar]/[stopMetadataSidecar] set up (any container on
 * it is equally isolated), not the emulator's own HTTP handling, which
 * [MetadataEmulatorTest]/[MetadataEmulatorContractTest] already cover in-process against the real
 * class.
 *
 * Same self-skip-locally / required-in-CI policy as the other contract suites.
 */
class MetadataSidecarIsolationContractTest {

  private val runToken = "sidecariso-" + java.util.UUID.randomUUID().toString().take(8)
  private val ownerLabel = "ms-workload.test-owner"

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
    for (c in connector.containers.list(labels = mapOf(ownerLabel to runToken))) {
      runCatching { connector.containers.remove(c.id, force = true) }
    }
    for (n in connector.networks.list(labels = mapOf(ownerLabel to runToken))) {
      runCatching { connector.networks.remove(n.id) }
    }
  }

  private fun tenantLabels(tenant: String) = mapOf(ownerLabel to runToken, "tenant" to tenant)

  /** A busybox `httpd` standing in for the real emulator image — see the class doc. */
  private fun fakeSidecarCmd(port: Int, tenant: String) =
      listOf(
          "sh",
          "-c",
          "mkdir -p /www && echo -n 'sidecar-response-$tenant' > /www/index.html && " +
              "httpd -f -p $port -h /www",
      )

  private suspend fun startFakeSidecar(
      connector: DockerConnector,
      tenant: String,
  ): MetadataSidecar =
      startMetadataSidecar(
          connector = connector,
          image = BUSYBOX,
          networkName = "ms-workload-$runToken-$tenant",
          env = emptyList(),
          labels = tenantLabels(tenant),
          port = SIDECAR_PORT,
          cmd = fakeSidecarCmd(SIDECAR_PORT, tenant),
      )

  /**
   * Fetches `http://[address]/` from a fresh busybox container attached to [network] — i.e. exactly
   * how a workload container would reach its sidecar's [MetadataSidecar.address]. Returns the body,
   * or null if the fetch failed (including a timeout: on a network with no route to [address] the
   * daemon drops the traffic rather than answering, so a short client-side timeout is what actually
   * bounds this).
   */
  private suspend fun fetchFrom(
      connector: DockerConnector,
      network: String,
      address: String,
      labels: Map<String, String>,
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
  fun `a workload container reaches its own tenant's sidecar over their shared network`() =
      withConnector { connector ->
        val sidecar = startFakeSidecar(connector, "solo")
        try {
          val response =
              fetchFrom(connector, sidecar.networkName, sidecar.address, tenantLabels("solo"))
          assertNotNull(response, "the workload container should reach its own sidecar")
          assertTrue("sidecar-response-solo" in response, "unexpected sidecar response: $response")
        } finally {
          stopMetadataSidecar(connector, sidecar)
        }
      }

  @Test
  fun `a container on a different tenant's network cannot reach another tenant's sidecar`() =
      withConnector { connector ->
        val tenantA = startFakeSidecar(connector, "a")
        val tenantB = startFakeSidecar(connector, "b")
        try {
          // Sanity check: tenant B's own workload really does reach tenant B's own sidecar — so a
          // failure below is isolation, not a broken fixture.
          val ownResponse =
              fetchFrom(connector, tenantB.networkName, tenantB.address, tenantLabels("b"))
          assertNotNull(ownResponse, "sanity check: tenant B should reach its own sidecar")
          assertTrue("sidecar-response-b" in ownResponse, "unexpected response: $ownResponse")

          // The actual claim: a container on tenant B's network cannot reach tenant A's sidecar.
          val crossTenantResponse =
              fetchFrom(connector, tenantB.networkName, tenantA.address, tenantLabels("b"))
          assertNull(
              crossTenantResponse,
              "a container on tenant B's network reached tenant A's sidecar; " +
                  "got: $crossTenantResponse",
          )
        } finally {
          stopMetadataSidecar(connector, tenantA)
          stopMetadataSidecar(connector, tenantB)
        }
      }

  private companion object {
    const val BUSYBOX = "busybox:latest"
    const val SIDECAR_PORT = 8080
  }
}
