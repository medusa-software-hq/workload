package software.medusa.workload.cli

import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig

/**
 * The M4-B3 `workload run` wiring end to end against a **real, local** Docker daemon: the in-CLI
 * metadata emulator bound to the host's primary IP, and a container that fetches a token from it
 * via the injected `GCE_METADATA_*` pointers — with no token in its own env. Uses a *fake* token
 * source (no GCP needed); B1 already proved a real `ComputeEngineCredentials` refreshes against the
 * emulator.
 *
 * **Requires a daemon on this host** (`DOCKER_CONTRACT_REQUIRED` — CI + the tux VM): the container
 * reaches the emulator via the host, which only works when the JVM *is* the Docker host, not Docker
 * Desktop. The long-job / live-revocation paths against real GCP are the story's hand-tests.
 */
class RunMetadataContractTest {

  private val runToken = "runmeta-" + java.util.UUID.randomUUID().toString().take(8)
  private val ownerLabel = "ms-workload.test-owner"
  private val fakeToken = "ya29.beacon-fake-token"

  private fun withLocalDaemon(block: suspend (DockerConnector) -> Unit) {
    if (System.getenv("DOCKER_CONTRACT_REQUIRED").isNullOrBlank()) {
      assumeTrue(false, "needs a local daemon (set DOCKER_CONTRACT_REQUIRED — CI/VM); skipping")
    }
    DockerConnector(DockerConnectorConfig.fromEnvironment()).use { connector ->
      try {
        runBlocking { connector.ping() }
      } catch (e: DockerConnectionException) {
        fail("DOCKER_CONTRACT_REQUIRED is set but no Docker daemon was reachable: ${e.message}")
      }
      runBlocking { connector.images.pull(BUSYBOX).collect {} }
      try {
        runBlocking { block(connector) }
      } finally {
        runBlocking {
          for (c in connector.containers.list(labels = mapOf(ownerLabel to runToken))) {
            runCatching { connector.containers.remove(c.id, force = true) }
          }
        }
      }
    }
  }

  @Test
  fun `a container fetches a token via the pointer env, with no token in its own env`() =
      withLocalDaemon { connector ->
        val primary = hostPrimaryAddress()
        MetadataEmulator(
                RefreshingTokenCache({
                  BrokeredToken(
                      accessToken = fakeToken,
                      expiresAt = Instant.now().plusSeconds(3600),
                      serviceAccountEmail = "runner@proj.iam.gserviceaccount.com",
                  )
                }),
                InetSocketAddress(primary, 0),
                ::isTrustedRunPeer,
            )
            .use { emu ->
              emu.start()
              val address = "${primary.hostAddress}:${emu.port}"
              val out = StringBuilder()
              val script =
                  "echo HOST=${'$'}GCE_METADATA_HOST; " +
                      "echo TOKENVARS=${'$'}(env | grep -c ACCESS_TOKEN); " +
                      "wget -q -O - --header 'Metadata-Flavor: Google' " +
                      "http://${'$'}GCE_METADATA_HOST/computeMetadata/v1/instance/service-accounts/default/token"

              runContainerToCompletion(
                  connector = connector,
                  image = BUSYBOX,
                  env = buildMetadataContainerEnv(emptyMap(), metadataPointerEnv(address)),
                  labels = mapOf(ownerLabel to runToken),
                  extraHosts = listOf("metadata.google.internal:${primary.hostAddress}"),
                  cmd = listOf("sh", "-c", script),
                  onStdout = { out.append(String(it)) },
                  onStderr = {},
              )

              val text = out.toString()
              assertTrue(fakeToken in text, "container should fetch the brokered token; got: $text")
              assertTrue("HOST=$address" in text, "GCE_METADATA_HOST should point at the emulator")
              assertTrue("TOKENVARS=0" in text, "no *_ACCESS_TOKEN var should be in the env")
            }
      }

  private companion object {
    const val BUSYBOX = "busybox:latest"
  }
}
