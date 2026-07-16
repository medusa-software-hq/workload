package software.medusa.workload.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.Server
import java.time.Duration
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val prefix = "test-prefix"
private const val registrationsPath = "/$prefix/worker/v1/registrations"
private const val selfStatusPath = "/$prefix/worker/v1/registrations/self"

/** End-to-end check of [RegistrationService] and [SelfStatusService] via a real running server. */
class RegistrationEndpointTest {

  private lateinit var fleetStore: FleetStore
  private lateinit var server: Server
  private lateinit var client: WebClient

  @BeforeTest
  fun start() {
    fleetStore = InMemoryFleetStore()
    server =
        buildServer(
            originRegex = """http://localhost(:\d+)?""",
            port = 0,
            workerApiPathPrefix = prefix,
            auth = NoOpAuthDecorator,
            counterStore = InMemoryWorkloadStore(),
            fleetStore = fleetStore,
            impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
            imageDigestResolver = AlwaysResolvedImageDigestResolver,
            registrationService =
                RegistrationService(
                    fleetStore,
                    // High enough that the non-rate-limit tests never trip it.
                    rateLimiter =
                        PerSourceIpRateLimiter(
                            maxRequestsPerWindow = 1000,
                            window = Duration.ofMinutes(1),
                        ),
                ),
            selfStatusService = SelfStatusService(fleetStore),
        )
    server.start().join()
    client = WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }

  @AfterTest
  fun stop() {
    server.stop().join()
  }

  private fun register(name: String = "worker-1"): RegisterWorkerResponse {
    val body =
        workerJson.encodeToString(RegisterWorkerRequest(name = name, hostname = "host.local"))
    val response = client.post(registrationsPath, body).aggregate().join()
    assertEquals(HttpStatus.OK, response.status())
    return workerJson.decodeFromString(response.contentUtf8())
  }

  private fun pollSelf(bearer: String) =
      client
          .execute(
              RequestHeaders.of(
                  HttpMethod.GET,
                  selfStatusPath,
                  "Authorization",
                  "Bearer $bearer",
              )
          )
          .aggregate()
          .join()

  @Test
  fun `registration returns a distinct secret per call`() {
    val first = register()
    val second = register()
    assertNotEquals(first.workerSecret, second.workerSecret)
    assertNotEquals(first.workerId, second.workerId)
  }

  @Test
  fun `a fresh secret round-trips against self-status as pending`() {
    val registered = register(name = "jakub-mbp")
    val response = pollSelf("${registered.workerId}.${registered.workerSecret}")

    assertEquals(HttpStatus.OK, response.status())
    val status = workerJson.decodeFromString<SelfStatusResponse>(response.contentUtf8())
    assertEquals("pending", status.status)
    assertEquals("jakub-mbp", status.name)
  }

  @Test
  fun `an approved worker polls as active`() =
      kotlinx.coroutines.runBlocking {
        val registered = register()
        fleetStore.approveWorker(
            WorkerId(java.util.UUID.fromString(registered.workerId)),
            approvedBy = "admin@example.com",
        )

        val response = pollSelf("${registered.workerId}.${registered.workerSecret}")
        val status = workerJson.decodeFromString<SelfStatusResponse>(response.contentUtf8())
        assertEquals("active", status.status)
      }

  @Test
  fun `an active worker's self status lists its granted profile ids`() =
      kotlinx.coroutines.runBlocking {
        val registered = register()
        val workerId = WorkerId(java.util.UUID.fromString(registered.workerId))
        fleetStore.approveWorker(workerId, approvedBy = "admin@example.com")
        fleetStore.createProfile(
            ProfileId("profile-a"),
            displayName = null,
            revision =
                NewProfileRevision("sa@example.iam.gserviceaccount.com", "admin@example.com"),
        )
        fleetStore.grant(workerId, ProfileId("profile-a"), grantedBy = "admin@example.com")

        val response = pollSelf("${registered.workerId}.${registered.workerSecret}")
        val status = workerJson.decodeFromString<SelfStatusResponse>(response.contentUtf8())
        assertEquals(listOf("profile-a"), status.grantedProfileIds)
      }

  @Test
  fun `a pending worker's self status has no granted profile ids`() {
    val registered = register()
    val response = pollSelf("${registered.workerId}.${registered.workerSecret}")
    val status = workerJson.decodeFromString<SelfStatusResponse>(response.contentUtf8())
    assertEquals(emptyList(), status.grantedProfileIds)
  }

  @Test
  fun `wrong secret is unauthorized with the existing error shape`() {
    val registered = register()
    val response = pollSelf("${registered.workerId}.wrong-secret")

    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
    assertEquals(
        WorkerErrorResponse("unauthorized"),
        workerJson.decodeFromString<WorkerErrorResponse>(response.contentUtf8()),
    )
  }

  @Test
  fun `unknown worker id is unauthorized`() {
    val response = pollSelf("${java.util.UUID.randomUUID()}.some-secret")
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `malformed bearer token is unauthorized`() {
    val response = pollSelf("not-a-valid-token")
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `missing Authorization header is unauthorized`() {
    val response = client.get(selfStatusPath).aggregate().join()
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `a rejected worker polls as unauthorized, same as an expired-TTL pending worker`() =
      kotlinx.coroutines.runBlocking {
        // rejectWorker() and TTL expiry (WorkerPendingExpiryTest) both converge on status =
        // REJECTED
        // as read by fleetStore.getWorker() — SelfStatusService can't tell them apart, by design.
        val registered = register()
        fleetStore.rejectWorker(WorkerId(java.util.UUID.fromString(registered.workerId)))

        val response = pollSelf("${registered.workerId}.${registered.workerSecret}")
        assertEquals(HttpStatus.UNAUTHORIZED, response.status())
      }

  @Test
  fun `rate limit kicks in and returns 429`() {
    val limitedServer =
        buildServer(
            originRegex = """http://localhost(:\d+)?""",
            port = 0,
            workerApiPathPrefix = prefix,
            auth = NoOpAuthDecorator,
            counterStore = InMemoryWorkloadStore(),
            fleetStore = InMemoryFleetStore(),
            impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
            imageDigestResolver = AlwaysResolvedImageDigestResolver,
            registrationService =
                RegistrationService(
                    InMemoryFleetStore(),
                    rateLimiter =
                        PerSourceIpRateLimiter(
                            maxRequestsPerWindow = 2,
                            window = Duration.ofMinutes(1),
                        ),
                ),
        )
    limitedServer.start().join()
    try {
      val limitedClient = WebClient.of("http://127.0.0.1:${limitedServer.activeLocalPort()}")
      val body = workerJson.encodeToString(RegisterWorkerRequest(name = "worker-1"))

      repeat(2) {
        val response = limitedClient.post(registrationsPath, body).aggregate().join()
        assertEquals(HttpStatus.OK, response.status())
      }

      val limited = limitedClient.post(registrationsPath, body).aggregate().join()
      assertEquals(HttpStatus.TOO_MANY_REQUESTS, limited.status())
    } finally {
      limitedServer.stop().join()
    }
  }
}
