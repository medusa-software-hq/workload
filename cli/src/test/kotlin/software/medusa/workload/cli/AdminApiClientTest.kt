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

  @Test
  fun `an unreachable API becomes a BrokerUnreachableException (no stack trace)`() {
    // Port 1 on loopback: nothing listens, so the connect fails — the admin client must translate
    // that transport failure the same way the worker client does, for the top-level clean message.
    val client = AdminApiClient("http://127.0.0.1:1", idTokenProvider = { "t" })
    val error = assertFailsWith<BrokerUnreachableException> { client.listProfiles() }
    assertTrue(error.message!!.contains("127.0.0.1:1"))
  }

  @Test
  fun `approveWorker posts the worker id and parses the returned worker`() {
    val api =
        StubApi(
            200,
            """{"worker":{"workerId":"w-1","name":"jakub-mac","status":"WORKER_STATUS_ACTIVE"}}""",
        )
    stub = api
    val worker = AdminApiClient(api.baseUrl, idTokenProvider = { "t" }).approveWorker("w-1")

    assertEquals("/medusa.workload.v1.FleetService/ApproveWorker", api.lastPath)
    assertEquals("""{"workerId":"w-1"}""", api.lastBody)
    assertEquals("WORKER_STATUS_ACTIVE", worker.status)
  }

  @Test
  fun `grantProfile posts both ids`() {
    val api = StubApi(200, "{}")
    stub = api
    AdminApiClient(api.baseUrl, idTokenProvider = { "t" }).grantProfile("w-1", "p-1")

    assertEquals("/medusa.workload.v1.FleetService/GrantProfile", api.lastPath)
    assertEquals("""{"workerId":"w-1","profileId":"p-1"}""", api.lastBody)
  }

  @Test
  fun `createProfile posts the full spec (no CAS) and parses the pinned revision`() {
    val api =
        StubApi(
            200,
            """{"profile":{"profileId":"p"},"revision":{"revision":1,"imageStatus":"IMAGE_STATUS_RESOLVED","dockerImageDigest":"sha256:abc"}}""",
        )
    stub = api
    val spec =
        ProfileRevisionSpec(
            targetServiceAccount = "sa@x",
            note = "n",
            dockerImage = "r/i:t",
            envVars = mapOf("K" to "V"),
        )
    val revision =
        AdminApiClient(api.baseUrl, idTokenProvider = { "t" }).createProfile("p", "Disp", spec)

    assertEquals("/medusa.workload.v1.FleetService/CreateProfile", api.lastPath)
    val body = api.lastBody!!
    assertTrue(""""profileId":"p"""" in body)
    assertTrue(""""displayName":"Disp"""" in body)
    assertTrue(""""targetServiceAccount":"sa@x"""" in body)
    assertTrue(""""dockerImage":"r/i:t"""" in body)
    assertTrue("expectedDockerImageDigest" !in body, "the CLI must never send the CAS token")
    assertEquals("sha256:abc", revision.dockerImageDigest)
  }

  @Test
  fun `updateProfile omits displayName and the CAS token`() {
    val api = StubApi(200, """{"revision":{"revision":7}}""")
    stub = api
    AdminApiClient(api.baseUrl, idTokenProvider = { "t" })
        .updateProfile("p", ProfileRevisionSpec(targetServiceAccount = "sa@x"))

    assertEquals("/medusa.workload.v1.FleetService/UpdateProfile", api.lastPath)
    val body = api.lastBody!!
    assertTrue(""""profileId":"p"""" in body)
    assertTrue("displayName" !in body)
    assertTrue("expectedDockerImageDigest" !in body)
  }

  @Test
  fun `listProfileRevisions parses revisions`() {
    val api =
        StubApi(
            200,
            """{"revisions":[{"profileId":"p","revision":1,"targetServiceAccount":"sa@x","envVars":{"K":"V"}}]}""",
        )
    stub = api
    val revisions = AdminApiClient(api.baseUrl, idTokenProvider = { "t" }).listProfileRevisions("p")

    assertEquals("""{"profileId":"p"}""", api.lastBody)
    assertEquals(1, revisions.size)
    assertEquals("sa@x", revisions[0].targetServiceAccount)
    assertEquals(mapOf("K" to "V"), revisions[0].envVars)
  }

  @Test
  fun `createEnrollmentToken posts the note, expiry, and approval flag and parses the token`() {
    val api =
        StubApi(
            200,
            """{"token":"wle_abc123","enrollmentToken":{"enrollmentTokenId":"et-1","note":"for Kuba's MBP","createdBy":"admin@medusa.software","expiresAt":"2026-07-26","requireApproval":true}}""",
        )
    stub = api
    val result =
        AdminApiClient(api.baseUrl, idTokenProvider = { "t" })
            .createEnrollmentToken(
                note = "for Kuba's MBP",
                expiresInDays = 7,
                requireApproval = true,
            )

    assertEquals("/medusa.workload.v1.FleetService/CreateEnrollmentToken", api.lastPath)
    assertEquals(
        """{"note":"for Kuba's MBP","expiresInDays":7,"requireApproval":true}""",
        api.lastBody,
    )
    assertEquals("wle_abc123", result.token)
    assertEquals("et-1", result.enrollmentToken.enrollmentTokenId)
    assertTrue(result.enrollmentToken.requireApproval)
  }

  @Test
  fun `listEnrollmentTokens parses outstanding tokens`() {
    val api =
        StubApi(
            200,
            """{"enrollmentTokens":[{"enrollmentTokenId":"et-1","note":"laptop","createdBy":"admin@x","expiresAt":"2026-07-26","requireApproval":false}]}""",
        )
    stub = api
    val tokens = AdminApiClient(api.baseUrl, idTokenProvider = { "t" }).listEnrollmentTokens()

    assertEquals("{}", api.lastBody)
    assertEquals(1, tokens.size)
    assertEquals("et-1", tokens[0].enrollmentTokenId)
    assertEquals("laptop", tokens[0].note)
  }

  @Test
  fun `revokeEnrollmentToken posts the token id`() {
    val api = StubApi(200, "{}")
    stub = api
    AdminApiClient(api.baseUrl, idTokenProvider = { "t" }).revokeEnrollmentToken("et-9")

    assertEquals("/medusa.workload.v1.FleetService/RevokeEnrollmentToken", api.lastPath)
    assertEquals("""{"enrollmentTokenId":"et-9"}""", api.lastBody)
  }
}
