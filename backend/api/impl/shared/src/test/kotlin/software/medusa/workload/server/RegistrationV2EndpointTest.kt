package software.medusa.workload.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.Server
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import software.medusa.workload.tokenformat.TokenKind
import software.medusa.workload.tokenformat.WorkloadToken

private const val v2RegistrationsPath = "/worker/v2/registrations"
private const val v2TokenPath = "/worker/v2/token"
private const val v2SelfPath = "/worker/v2/registrations/self"

/**
 * End-to-end check of [RegistrationServiceV2] and the un-gated v2 worker plane via a real server.
 */
class RegistrationV2EndpointTest {

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
            auth = NoOpAuthDecorator,
            fleetStore = fleetStore,
            impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
            imageDigestResolver = AlwaysResolvedImageDigestResolver,
            workerTokenBroker = WorkerTokenBrokerService(fleetStore, FakeTokenMinter),
            selfStatusService = SelfStatusService(fleetStore),
            v2RegistrationService =
                RegistrationServiceV2(
                    fleetStore,
                    // High enough that the non-rate-limit tests never trip it.
                    rateLimiter =
                        PerSourceIpRateLimiter(
                            maxRequestsPerWindow = 1000,
                            window = Duration.ofMinutes(1),
                        ),
                ),
        )
    server.start().join()
    client = WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }

  @AfterTest
  fun stop() {
    server.stop().join()
  }

  /** Mints an enrollment token straight into the store and returns the plaintext `wle_` value. */
  private fun mintEnrollmentToken(
      requireApproval: Boolean = false,
      expiresAt: Instant = Instant.now().plusSeconds(3600),
  ): String {
    val plaintext = WorkloadToken.generate(TokenKind.ENROLLMENT)
    runBlocking {
      fleetStore.createEnrollmentToken(
          NewEnrollmentToken(
              tokenHash = hashEnrollmentToken(plaintext),
              note = "test",
              createdBy = "admin@example.com",
              expiresAt = expiresAt,
              requireApproval = requireApproval,
          )
      )
    }
    return plaintext
  }

  private fun register(
      enrollmentToken: String?,
      name: String = "worker-1",
  ): AggregatedHttpResponse {
    val headers =
        if (enrollmentToken == null) {
          RequestHeaders.of(HttpMethod.POST, v2RegistrationsPath)
        } else {
          RequestHeaders.of(
              HttpMethod.POST,
              v2RegistrationsPath,
              "Authorization",
              "Bearer $enrollmentToken",
          )
        }
    val body =
        workerJson.encodeToString(RegisterWorkerV2Request(name = name, hostname = "host.local"))
    return client.execute(headers, body).aggregate().join()
  }

  @Test
  fun `a valid enrollment token exchanges for an active v2 worker whose secret works`() =
      runBlocking {
        val response = register(mintEnrollmentToken())
        assertEquals(HttpStatus.OK, response.status())
        val registered =
            workerJson.decodeFromString<RegisterWorkerV2Response>(response.contentUtf8())

        // The minted secret is a wlw_ worker token; no confirmation code is returned.
        assertTrue(WorkloadToken.isValid(registered.workerSecret, TokenKind.WORKER))

        val worker = fleetStore.getWorker(WorkerId(UUID.fromString(registered.workerId)))
        assertNotNull(worker)
        assertEquals(WorkerStatus.ACTIVE, worker.status)
        assertEquals(RegisteredVia.V2, worker.registeredVia)
        assertEquals("127.0.0.1", worker.sourceIp)

        // The wlw_ secret authenticates through the shared worker-plane auth: self-status is
        // active.
        val self =
            client
                .execute(
                    RequestHeaders.of(
                        HttpMethod.GET,
                        v2SelfPath,
                        "Authorization",
                        "Bearer ${registered.workerId}.${registered.workerSecret}",
                    )
                )
                .aggregate()
                .join()
        assertEquals(HttpStatus.OK, self.status())
        assertEquals(
            "active",
            workerJson.decodeFromString<SelfStatusResponse>(self.contentUtf8()).status,
        )
      }

  @Test
  fun `a v2 worker can claim a token for a granted profile`() = runBlocking {
    val registered =
        workerJson.decodeFromString<RegisterWorkerV2Response>(
            register(mintEnrollmentToken()).contentUtf8()
        )
    val workerId = WorkerId(UUID.fromString(registered.workerId))
    fleetStore.createProfile(
        ProfileId("profile-a"),
        displayName = null,
        revision = NewProfileRevision("sa@example.iam.gserviceaccount.com", "admin@example.com"),
    )
    // FleetServiceImpl.createProfile verifies synchronously; do the same so the revision is
    // claimable (an UNVERIFIED revision is refused by the claim path).
    fleetStore.recordVerification(ProfileId("profile-a"), revision = 1, VerificationStatus.VERIFIED)
    fleetStore.grant(workerId, ProfileId("profile-a"), grantedBy = "admin@example.com")

    val claim =
        client
            .execute(
                RequestHeaders.of(
                    HttpMethod.POST,
                    v2TokenPath,
                    "Authorization",
                    "Bearer ${registered.workerId}.${registered.workerSecret}",
                ),
                workerJson.encodeToString(TokenClaimRequest("profile-a")),
            )
            .aggregate()
            .join()
    assertEquals(HttpStatus.OK, claim.status())
  }

  @Test
  fun `reusing a burnt token returns a bare 404`() {
    val token = mintEnrollmentToken()
    assertEquals(HttpStatus.OK, register(token).status())

    val second = register(token)
    assertEquals(HttpStatus.NOT_FOUND, second.status())
    assertEquals("", second.contentUtf8())
  }

  @Test
  fun `an expired token returns a bare 404`() {
    val token = mintEnrollmentToken(expiresAt = Instant.now().minusSeconds(30))
    val response = register(token)
    assertEquals(HttpStatus.NOT_FOUND, response.status())
    assertEquals("", response.contentUtf8())
  }

  @Test
  fun `a malformed token returns a bare 404 without touching the store`() {
    val response = register("wle_not-a-real-token")
    assertEquals(HttpStatus.NOT_FOUND, response.status())
    assertEquals("", response.contentUtf8())
    runBlocking { assertTrue(fleetStore.listWorkers().isEmpty()) }
  }

  @Test
  fun `a missing Authorization header returns a bare 404`() {
    val response = register(enrollmentToken = null)
    assertEquals(HttpStatus.NOT_FOUND, response.status())
    assertEquals("", response.contentUtf8())
  }

  @Test
  fun `a require_approval token creates a pending worker with its source IP`() = runBlocking {
    val response = register(mintEnrollmentToken(requireApproval = true))
    assertEquals(HttpStatus.OK, response.status())
    val registered = workerJson.decodeFromString<RegisterWorkerV2Response>(response.contentUtf8())

    val worker = fleetStore.getWorker(WorkerId(UUID.fromString(registered.workerId)))
    assertNotNull(worker)
    assertEquals(WorkerStatus.PENDING, worker.status)
    assertEquals("127.0.0.1", worker.sourceIp)
  }
}
