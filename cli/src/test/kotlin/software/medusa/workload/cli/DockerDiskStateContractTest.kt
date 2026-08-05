package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.workload.docker.DockerAuthResolver
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig
import software.medusa.workload.runtime.brokeredRegistryAuth

/**
 * Drydock's disk-state audit, re-run as a test (story 05 AC): brokered auth must leave **nothing**
 * on disk. `docker login` writes credentials into `~/.docker/config.json`; a plug-and-play worker
 * must not, or a machine would accumulate registry credentials as a side effect of running work.
 *
 * Uses `$DOCKER_CONFIG` to point the connector's resolver at a scratch directory, so the audit is
 * exact rather than a guess about the developer's real home directory.
 */
class DockerDiskStateContractTest {

  private fun withConnector(configDir: Path, block: suspend (DockerConnector) -> Unit) {
    val config = DockerConnectorConfig.fromEnvironment()
    // Point credential resolution at the scratch dir, exactly as $DOCKER_CONFIG would.
    val resolver = DockerAuthResolver(configDir.resolve("config.json"))
    DockerConnector(config, resolver).use { connector ->
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
      runBlocking { block(connector) }
    }
  }

  @Test
  fun `a brokered pull writes nothing to the docker config directory`() {
    val configDir = Files.createTempDirectory("docker-disk-audit")
    withConnector(configDir) { connector ->
      // A brokered auth config is what `workload run` passes for a Google registry. Docker Hub
      // rejects it, but the point is what lands on disk, not whether the pull succeeds.
      val auth = brokeredRegistryAuth("us-docker.pkg.dev/p/repo/app@sha256:abc", "ya29.fake-token")
      runCatching { connector.images.pull("busybox:latest", auth).toList() }

      val entries = Files.list(configDir).use { it.toList() }
      assertTrue(
          entries.isEmpty(),
          "brokered auth must not write anything to the docker config dir, found: $entries",
      )
      assertFalse(Files.exists(configDir.resolve("config.json")), "config.json must not be created")
    }
  }

  @Test
  fun `an anonymous pull leaves an existing config file byte-identical`() {
    val configDir = Files.createTempDirectory("docker-disk-audit")
    val configFile = configDir.resolve("config.json")
    val original = """{"auths":{}}"""
    Files.writeString(configFile, original)

    withConnector(configDir) { connector -> connector.images.pull("busybox:latest").toList() }

    assertEquals(original, Files.readString(configFile), "the pull must not rewrite config.json")
  }

  @Test
  fun `resolving credentials never creates a config file that was absent`() {
    val configDir = Files.createTempDirectory("docker-disk-audit")
    val resolver = DockerAuthResolver(configDir.resolve("config.json"))

    // No config, no helper: anonymous, and crucially no file gets written as a side effect.
    assertEquals(null, resolver.resolve("us-docker.pkg.dev"))
    assertFalse(Files.exists(configDir.resolve("config.json")))
  }
}
