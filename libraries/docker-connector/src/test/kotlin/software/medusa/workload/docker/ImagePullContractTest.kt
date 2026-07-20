package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * Pull contract tests against a **real** Docker daemon (and, for the pull itself, a real registry —
 * these use Docker Hub's public `busybox`, so they need network but no credentials).
 *
 * Same policy as the other contract suites: self-skip without a daemon, hard failure when
 * `DOCKER_CONTRACT_REQUIRED` is set.
 */
class ImagePullContractTest {

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
      runBlocking { block(connector) }
    }
  }

  @Test
  fun `pulling a public image emits progress and completes`() = withConnector { connector ->
    val progress = connector.images.pull(BUSYBOX).toList()
    assertTrue(progress.isNotEmpty(), "a pull should report progress")
    assertTrue(
        progress.any { !it.status.isNullOrBlank() },
        "at least one record should carry a status",
    )
    // The image is now present, so the whole point of the pull held.
    val inspected = connector.images.inspect(BUSYBOX)
    assertTrue(inspected.id.isNotBlank())
  }

  @Test
  fun `pulling an already-cached image is a fast no-op that still succeeds`() =
      withConnector { connector ->
        connector.images.pull(BUSYBOX).toList()
        val started = System.nanoTime()
        val progress = connector.images.pull(BUSYBOX).toList()
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertTrue(progress.isNotEmpty())
        // Not a strict SLA — just proves the cached path doesn't re-download the layers.
        assertTrue(elapsedMs < 30_000, "a cached pull took ${elapsedMs}ms")
      }

  /**
   * The AC: a nonexistent tag must fail with a *typed* error and never look like a success.
   *
   * Which typed error depends on the daemon: this one (API 1.55) answers a genuine HTTP 404, so it
   * surfaces as [DockerApiException]; older daemons answer 200 and report the failure as an
   * in-stream `error` record, which surfaces as [DockerPullException]. Both are handled, and
   * `ImagePullErrorMappingTest` covers the in-stream shape directly (daemon-independently) since a
   * modern daemon won't produce it for not-found. What matters here is that it *fails*.
   */
  @Test
  fun `a nonexistent tag fails with a typed error instead of reporting success`() =
      withConnector { connector ->
        val e =
            assertFailsWith<DockerConnectorException> {
              connector.images.pull("busybox:definitely-not-a-real-tag-9f8e7d").toList()
            }
        assertTrue(e.message!!.isNotBlank(), "the daemon's own error text should survive")
        assertTrue(
            e is DockerApiException || e is DockerPullException,
            "expected a pull/API failure, got ${e::class.simpleName}: ${e.message}",
        )
      }

  @Test
  fun `a nonexistent repository fails with a typed error`() = withConnector { connector ->
    val e =
        assertFailsWith<DockerConnectorException> {
          connector.images.pull("medusasoftwarehq/definitely-no-such-repo-9f8e7d:latest").toList()
        }
    assertTrue(
        e is DockerApiException || e is DockerPullException,
        "expected a pull/API failure, got ${e::class.simpleName}",
    )
  }

  @Test
  fun `inspect of an absent image is a typed 404, not a crash`() = withConnector { connector ->
    val e =
        assertFailsWith<DockerApiException> {
          connector.images.inspect("definitely-no-such-image-9f8e7d:latest")
        }
    assertEquals(404, e.statusCode)
  }

  @Test
  fun `pulling by digest resolves the same image`() = withConnector { connector ->
    connector.images.pull(BUSYBOX).toList()
    val digestRef =
        connector.images.inspect(BUSYBOX).repoDigests.firstOrNull()
            ?: return@withConnector // some daemons report no repoDigests for a local build
    connector.images.pull(digestRef).toList()
    assertTrue(connector.images.inspect(digestRef).id.isNotBlank())
  }

  private companion object {
    const val BUSYBOX = "busybox:latest"
  }
}
