package software.medusa.workload.docker

import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * The regression tests for the false-success bug, driven through a fake engine so they don't depend
 * on a daemon (or on which failures a given daemon version chooses to report as an HTTP status).
 *
 * The trap: `POST /images/create` answers **200** and then reports the real failure as an `error`
 * record inside the progress stream. A client that trusts the status code — or that drains the
 * stream without reading it, as docker-py's high-level `images.pull` does — calls a failed pull a
 * success. These assert the connector refuses to.
 */
class ImagePullErrorMappingTest {

  /** A [DockerEngine] that replays a canned response body for `streamBytes`. */
  private class FakeEngine(private val body: String) : DockerEngine {
    var lastPath: String? = null
    var lastHeaders: Map<String, String> = emptyMap()

    override val json = Json { ignoreUnknownKeys = true }

    override suspend fun versionedPath(path: String) = "/v1.51$path"

    override suspend fun exchange(
        method: HttpMethod,
        pathWithQuery: String,
        jsonBody: String?,
    ): AggregatedHttpResponse = AggregatedHttpResponse.of(HttpStatus.OK)

    override fun streamBytes(
        method: HttpMethod,
        pathWithQuery: String,
        headers: Map<String, String>,
    ): Flow<ByteArray> {
      lastPath = pathWithQuery
      lastHeaders = headers
      // Deliberately split mid-record so the splitter is exercised too.
      return flowOf(*body.chunked(7).map { it.toByteArray() }.toTypedArray())
    }
  }

  private fun imageApi(body: String, resolver: DockerAuthResolver = noAuthResolver()) =
      FakeEngine(body).let { it to ImageApi(it, resolver) }

  private fun noAuthResolver() =
      DockerAuthResolver(java.nio.file.Path.of("/nonexistent/config.json")) { _, _ ->
        HelperResult(0, "{}", "")
      }

  @Test
  fun `an in-stream error after successful progress still fails the pull`() {
    // This is the exact shape that fools a status-code-only client: 200, real progress, then an
    // error record at the end.
    val body =
        """
        {"status":"Pulling from library/app","id":"latest"}
        {"status":"Downloading","id":"abc123","progressDetail":{"current":10,"total":100}}
        {"errorDetail":{"message":"toomanyrequests: rate limit exceeded"},"error":"toomanyrequests: rate limit exceeded"}
        """
            .trimIndent()
    val (_, images) = imageApi(body)

    val e =
        assertFailsWith<DockerPullException> { runBlocking { images.pull("app:latest").toList() } }
    assertTrue("rate limit exceeded" in e.message!!, e.message!!)
  }

  @Test
  fun `an in-stream error is raised even though the HTTP status was a success`() {
    val body = """{"error":"manifest unknown","errorDetail":{"message":"manifest unknown"}}"""
    val (_, images) = imageApi(body)

    val e =
        assertFailsWith<DockerPullException> { runBlocking { images.pull("app:nope").toList() } }
    assertEquals("manifest unknown", e.message)
  }

  @Test
  fun `errorDetail is surfaced when it adds detail beyond the summary`() {
    val body =
        """{"error":"pull failed","errorDetail":{"message":"unauthorized: authentication required"}}"""
    val (_, images) = imageApi(body)

    val e = assertFailsWith<DockerPullException> { runBlocking { images.pull("app:x").toList() } }
    assertEquals("unauthorized: authentication required", e.detail)
  }

  @Test
  fun `a clean stream emits every progress record`() {
    val body =
        """
        {"status":"Pulling from library/app","id":"latest"}
        {"status":"Downloading","id":"abc123","progressDetail":{"current":10,"total":100}}
        {"status":"Pull complete","id":"abc123"}
        """
            .trimIndent()
    val (_, images) = imageApi(body)

    val progress = runBlocking { images.pull("app:latest").toList() }
    assertEquals(3, progress.size)
    assertEquals("Downloading", progress[1].status)
    assertEquals("abc123", progress[1].id)
    assertEquals(10, progress[1].progressDetail?.current)
    assertEquals(100, progress[1].progressDetail?.total)
  }

  @Test
  fun `a digest ref is sent as fromImage plus tag`() {
    val (engine, images) = imageApi("""{"status":"ok"}""")
    runBlocking { images.pull("us-docker.pkg.dev/p/repo/app@sha256:abc").toList() }

    val path = engine.lastPath!!
    assertTrue("fromImage=us-docker.pkg.dev%2Fp%2Frepo%2Fapp" in path, path)
    assertTrue("tag=sha256%3Aabc" in path, path)
  }

  @Test
  fun `an explicit authConfig overrides credential lookup and rides X-Registry-Auth`() {
    val (engine, images) = imageApi("""{"status":"ok"}""")
    val auth = RegistryAuth(serverAddress = "reg.example", username = "u", password = "p")
    runBlocking { images.pull("reg.example/app:v1", auth).toList() }

    assertEquals(auth.toHeaderValue(), engine.lastHeaders["X-Registry-Auth"])
  }

  @Test
  fun `an anonymous pull sends no auth header`() {
    val (engine, images) = imageApi("""{"status":"ok"}""")
    runBlocking { images.pull("busybox:latest").toList() }

    assertTrue(engine.lastHeaders.isEmpty(), "no credentials resolved => no X-Registry-Auth")
  }
}
