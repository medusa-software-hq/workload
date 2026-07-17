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

/** A canned in-process stand-in for the unframed FleetService endpoint. */
private class StubApi(private val status: Int, private val responseBody: String) {
  val server: HttpServer =
      HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
  var lastPath: String? = null
  var lastAuthorization: String? = null
  var lastBody: String? = null
  var lastContentType: String? = null

  init {
    server.createContext("/") { exchange ->
      lastPath = exchange.requestURI.path
      lastAuthorization = exchange.requestHeaders.getFirst("Authorization")
      lastContentType = exchange.requestHeaders.getFirst("Content-Type")
      lastBody = exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
      val bytes = responseBody.toByteArray(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.executor = null
    server.start()
  }

  val baseUrl: String
    get() = "http://127.0.0.1:${server.address.port}"

  fun stop() = server.stop(0)
}

class AdminApiClientTest {
  private var stub: StubApi? = null

  @AfterTest fun cleanup() = stub?.stop() ?: Unit

  @Test
  fun `listProfiles posts an authenticated json request and parses the response`() {
    val api =
        StubApi(
            200,
            """{"profiles":[
                {"profileId":"hand-test-1","displayName":"Hand test","latestRevision":2,"archived":false,"createdAt":"2026-07-17"},
                {"profileId":"legacy-2","latestRevision":1,"archived":true,"createdAt":"2026-01-02"}
            ]}""",
        )
    stub = api
    val client = AdminApiClient(api.baseUrl, idTokenProvider = { "tok-123" })

    val profiles = client.listProfiles()

    // Request shaping.
    assertEquals("/medusa.workload.v1.FleetService/ListProfiles", api.lastPath)
    assertEquals("Bearer tok-123", api.lastAuthorization)
    assertEquals("application/json", api.lastContentType)
    assertEquals("{}", api.lastBody)

    // Response parsing.
    assertEquals(2, profiles.size)
    assertEquals("hand-test-1", profiles[0].profileId)
    assertEquals(2, profiles[0].latestRevision)
    assertTrue(!profiles[0].archived)
    assertTrue(profiles[1].archived)
  }

  @Test
  fun `a 401 becomes a friendly identity-rejected error`() {
    val api = StubApi(401, "unauthorized")
    stub = api
    val client = AdminApiClient(api.baseUrl, idTokenProvider = { "tok" })
    val error = assertFailsWith<AdminApiException> { client.listProfiles() }
    assertEquals(401, error.statusCode)
    assertTrue(error.message!!.contains("admin login"))
  }
}
