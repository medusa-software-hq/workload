package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Contract tests for the M7-00 GC primitives against a **real** Docker daemon: `containers.prune`,
 * `images.list`/`tag`/`remove`, and `systemDf`. Same self-skip-locally / required-in-CI policy as
 * the other contract tests. Every container and every throwaway tag is labelled/namespaced with a
 * per-run token and cleaned up in a `finally`, so a failure can't leak state into later runs.
 */
class ImageGcAndPruneContractTest {

  private val runToken = "test-" + java.util.UUID.randomUUID().toString().take(8)
  private val ownerLabel = "ms-workload.test-owner"
  private val throwawayRepo = "ms-workload-gc-test/$runToken"

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
        runBlocking {
          for (summary in connector.containers.list(labels = mapOf(ownerLabel to runToken))) {
            runCatching { connector.containers.remove(summary.id, force = true) }
          }
          // Untag any throwaway refs this test created; never touch the shared busybox image
          // itself.
          for (image in runCatching { connector.images.list() }.getOrDefault(emptyList())) {
            for (ref in (image.repoTags ?: emptyList())) {
              if (ref.startsWith(throwawayRepo)) runCatching { connector.images.remove(ref) }
            }
          }
        }
      }
    }
  }

  @Test
  fun `prune removes our exited labeled containers but spares running and unlabeled ones`() =
      withConnector { connector ->
        val containers = connector.containers
        // An exited, labeled container — the reap target.
        val exited =
            containers.create(
                image = BUSYBOX,
                cmd = listOf("sh", "-c", "exit 0"),
                labels = mapOf(ownerLabel to runToken),
                autoRemove = false,
            )
        containers.start(exited.id)
        containers.wait(exited.id)

        // A running, labeled container — prune must NOT touch it.
        val running =
            containers.create(
                image = BUSYBOX,
                cmd = listOf("sh", "-c", "sleep 120"),
                labels = mapOf(ownerLabel to runToken),
                autoRemove = false,
            )
        containers.start(running.id)

        val result = containers.prune(labels = mapOf(ownerLabel to runToken))

        assertTrue(
            result.containersDeleted.orEmpty().any { it == exited.id },
            "prune should report the exited container as deleted",
        )
        val remaining = containers.list(labels = mapOf(ownerLabel to runToken)).map { it.id }
        assertFalse(exited.id in remaining, "exited labeled container should be pruned")
        assertTrue(running.id in remaining, "running container must survive prune")
      }

  @Test
  fun `image list, tag and remove-by-reference round-trip without deleting the base image`() =
      withConnector { connector ->
        val images = connector.images
        val ref = "$throwawayRepo:solo"
        images.tag(BUSYBOX, throwawayRepo, "solo")

        assertTrue(
            images.list().any { (it.repoTags ?: emptyList()).contains(ref) },
            "the throwaway tag should be listed after tag()",
        )

        images.remove(ref)

        assertFalse(
            images.list().any { (it.repoTags ?: emptyList()).contains(ref) },
            "removing the reference should untag it",
        )
        // Untagging our ref must leave the shared busybox image itself intact.
        assertTrue(
            images.list().any { (it.repoTags ?: emptyList()).contains(BUSYBOX) },
            "busybox:latest must survive removal of an unrelated throwaway tag",
        )
      }

  @Test
  fun `system df reports image count and on-disk bytes`() = withConnector { connector ->
    val df = connector.systemDf()
    assertTrue(df.imageCount > 0, "df should count at least the pulled busybox image")
    assertTrue(df.layersSize > 0, "df should report non-zero on-disk image bytes")
  }

  private companion object {
    const val BUSYBOX = "busybox:latest"
  }
}
