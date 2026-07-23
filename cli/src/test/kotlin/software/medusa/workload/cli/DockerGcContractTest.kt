package software.medusa.workload.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig

/**
 * End-to-end GC against a **real** Docker daemon (M7-00 AC: "two bumps leave one image per managed
 * repo; an unrelated image is untouched"). Two distinct base images (busybox, alpine) are tagged as
 * two "revisions" of one throwaway repo; the sweep — keeping the in-use revision — must untag the
 * superseded one and nothing else. Self-skips without a reachable daemon (hard-fails in CI, where
 * `DOCKER_CONTRACT_REQUIRED` is set). Only the throwaway tags are ever removed; the shared base
 * images survive, so the test is safe on a developer's daemon too.
 */
class DockerGcContractTest {

  private val runToken = java.util.UUID.randomUUID().toString().take(8)
  private val repo = "ms-workload-gc-test/$runToken"

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
          fail("DOCKER_CONTRACT_REQUIRED is set but no Docker daemon was reachable.")
        }
        assumeTrue(false, "no reachable Docker daemon; skipping contract test")
      }
      try {
        runBlocking { block(connector) }
      } finally {
        runBlocking {
          for (image in runCatching { connector.images.list() }.getOrDefault(emptyList())) {
            for (ref in (image.repoTags ?: emptyList())) {
              if (ref.startsWith(repo)) runCatching { connector.images.remove(ref) }
            }
          }
        }
      }
    }
  }

  @Test
  fun `sweeping a repo untags the superseded revision, keeping the in-use one and unrelated images`() =
      withConnector { connector ->
        val images = connector.images
        images.pull("busybox:latest").collect {}
        images.pull("alpine:latest").collect {}

        // Two "revisions" of our throwaway repo, backed by two genuinely-distinct images.
        images.tag("busybox:latest", repo, "rev1")
        images.tag("alpine:latest", repo, "rev2")
        val keptId = images.inspect("$repo:rev2").id // alpine — mark it as the in-use revision

        val deleted =
            sweepRepository(
                connector = connector,
                repository = repo,
                keepDigests = emptySet(),
                inUse = setOf(keptId),
                warn = {},
            )

        assertEquals(1, deleted, "exactly the one superseded, not-in-use revision should be swept")

        val tags = images.list().flatMap { it.repoTags ?: emptyList() }
        assertFalse("$repo:rev1" in tags, "the superseded revision must be untagged")
        assertTrue("$repo:rev2" in tags, "the in-use revision must survive")
        assertTrue("busybox:latest" in tags, "the unrelated base image must be untouched")
        assertTrue("alpine:latest" in tags, "the unrelated base image must be untouched")
      }

  @Test
  fun `garbageCollectAfterRun records the repo as managed and never throws`() =
      withConnector { connector ->
        val configDir = Files.createTempDirectory("gc-run-$runToken")
        connector.images.pull("busybox:latest").collect {}
        val digest =
            connector.images.inspect("busybox:latest").repoDigests.first().substringAfterLast('@')

        // keepDigest = busybox's own digest, so nothing of busybox is swept; the point is that the
        // repo gets recorded for a later `workload prune` and the call completes cleanly.
        garbageCollectAfterRun(connector, configDir, "busybox", digest, warn = {})

        assertTrue("busybox" in loadManagedRepos(configDir), "the run's repo should be recorded")
      }
}
