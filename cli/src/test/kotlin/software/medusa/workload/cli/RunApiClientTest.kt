package software.medusa.workload.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A canned in-process stand-in for the worker-plane run endpoints. */
private class RunStubApi(private val status: Int, private val responseBody: String) {
  val server: HttpServer =
      HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
  var lastPath: String? = null
  var lastAuthorization: String? = null
  var lastBody: String? = null

  init {
    server.createContext("/") { exchange ->
      lastPath = exchange.requestURI.path
      lastAuthorization = exchange.requestHeaders.getFirst("Authorization")
      lastBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
      val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
      // A 204 must carry no body length header, matching the real server.
      if (status == 204) exchange.sendResponseHeaders(204, -1)
      else {
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
    }
    server.executor = null
    server.start()
  }

  val baseUrl: String
    get() = "http://127.0.0.1:${server.address.port}"

  fun stop() = server.stop(0)
}

class RunApiClientTest {
  private var stub: RunStubApi? = null

  @AfterTest fun cleanup() = stub?.stop() ?: Unit

  @Test
  fun `createRun posts an authenticated request and parses the run id and cadence`() {
    val api =
        RunStubApi(
            200,
            """{"runId":"11111111-1111-1111-1111-111111111111","heartbeatIntervalSeconds":30}""",
        )
    stub = api

    val response = createRun(api.baseUrl, "wid", "wsecret", "my-profile", 4, "run", "sha256:abc")

    assertEquals("/worker/v2/runs", api.lastPath)
    assertEquals("Bearer wid.wsecret", api.lastAuthorization)
    assertTrue(api.lastBody!!.contains("\"profileId\":\"my-profile\""))
    assertTrue(api.lastBody!!.contains("\"revision\":4"))
    assertTrue(api.lastBody!!.contains("\"kind\":\"run\""))
    assertTrue(api.lastBody!!.contains("\"imageDigest\":\"sha256:abc\""))
    assertEquals("11111111-1111-1111-1111-111111111111", response.runId)
    assertEquals(30, response.heartbeatIntervalSeconds)
  }

  @Test
  fun `heartbeatRun targets the run's heartbeat path and accepts 204`() {
    val api = RunStubApi(204, "")
    stub = api
    heartbeatRun(api.baseUrl, "wid", "wsecret", "run-123")
    assertEquals("/worker/v2/runs/run-123/heartbeat", api.lastPath)
    assertEquals("Bearer wid.wsecret", api.lastAuthorization)
  }

  @Test
  fun `endRun sends the exit code to the run's end path and accepts 204`() {
    val api = RunStubApi(204, "")
    stub = api
    endRun(api.baseUrl, "wid", "wsecret", "run-123", 0)
    assertEquals("/worker/v2/runs/run-123/end", api.lastPath)
    assertEquals("""{"exitCode":0}""", api.lastBody)
  }

  @Test
  fun `endRun serializes a null exit code`() {
    val api = RunStubApi(204, "")
    stub = api
    endRun(api.baseUrl, "wid", "wsecret", "run-123", null)
    assertEquals("""{"exitCode":null}""", api.lastBody)
  }

  @Test
  fun `a non-204 heartbeat surfaces as a WorkerApiException with the broker's error code`() {
    val api = RunStubApi(403, """{"error":"not_your_run"}""")
    stub = api
    val e =
        assertFailsWith<WorkerApiException> { heartbeatRun(api.baseUrl, "wid", "wsecret", "run-9") }
    assertEquals(403, e.statusCode)
    assertEquals("not_your_run", e.errorCode)
  }

  @Test
  fun `an unreachable broker surfaces as BrokerUnreachableException`() {
    assertFailsWith<BrokerUnreachableException> {
      createRun("http://127.0.0.1:1", "wid", "wsecret", "p", 1, "run", null)
    }
  }
}
