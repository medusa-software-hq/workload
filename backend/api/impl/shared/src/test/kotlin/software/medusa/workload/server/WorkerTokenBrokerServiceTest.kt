package software.medusa.workload.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.Server
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val prefix = "test-prefix"
private const val tokenPath = "/$prefix/worker/v1/token"

/** End-to-end check of [WorkerTokenBrokerService] via a real running server. */
class WorkerTokenBrokerServiceTest {

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
            workerTokenBroker = WorkerTokenBrokerService(fleetStore, FakeTokenMinter),
        )
    server.start().join()
    client = WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }

  @AfterTest
  fun stop() {
    server.stop().join()
  }

  private fun claim(
      bearer: String,
      profileId: String?,
  ): com.linecorp.armeria.common.AggregatedHttpResponse {
    val body =
        if (profileId == null) "{}" else workerJson.encodeToString(TokenClaimRequest(profileId))
    return client
        .execute(
            RequestHeaders.of(
                HttpMethod.POST,
                tokenPath,
                "Authorization",
                "Bearer $bearer",
                "Content-Type",
                "application/json",
            ),
            body,
        )
        .aggregate()
        .join()
  }

  private fun registerActiveWorker(name: String = "worker-1"): Pair<WorkerId, String> =
      runBlocking {
        val secret = generateWorkerSecret()
        val worker =
            fleetStore.createWorker(
                NewWorker(
                    secretHash = hashWorkerSecret(secret),
                    name = name,
                    hostname = null,
                    os = null,
                    cliVersion = null,
                    confirmationCode = "1234",
                )
            )
        fleetStore.approveWorker(worker.workerId, approvedBy = "admin@example.com")
        worker.workerId to secret
      }

  private fun createGrantedProfile(
      workerId: WorkerId,
      targetServiceAccount: String = "target@example.iam.gserviceaccount.com",
  ): ProfileId = runBlocking {
    val profileId = ProfileId("profile-${UUID.randomUUID()}")
    fleetStore.createProfile(
        profileId,
        displayName = null,
        revision = NewProfileRevision(targetServiceAccount, createdBy = "admin@example.com"),
    )
    // FleetServiceImpl.createProfile always verifies synchronously; do the same here so a
    // freshly created test profile is claimable, same as it would be in production.
    fleetStore.recordVerification(profileId, revision = 1, VerificationStatus.VERIFIED)
    fleetStore.grant(workerId, profileId, grantedBy = "admin@example.com")
    profileId
  }

  @Test
  fun `approved and granted worker gets a working token naming the profile, revision, and SA`() {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId, "target@example.iam.gserviceaccount.com")

    val response = claim("${workerId.value}.$secret", profileId.value)

    assertEquals(HttpStatus.OK, response.status())
    val body = workerJson.decodeFromString<WorkerTokenResponse>(response.contentUtf8())
    assertEquals(profileId.value, body.profileId)
    assertEquals(1, body.revision)
    assertEquals("target@example.iam.gserviceaccount.com", body.serviceAccount)
    assertEquals("fake-access-token", body.accessToken)
  }

  @Test
  fun `pending worker is unauthorized`() = runBlocking {
    val secret = generateWorkerSecret()
    val worker =
        fleetStore.createWorker(
            NewWorker(
                secretHash = hashWorkerSecret(secret),
                name = "worker-1",
                hostname = null,
                os = null,
                cliVersion = null,
                confirmationCode = "1234",
            )
        )

    val response = claim("${worker.workerId.value}.$secret", "some-profile")
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `rejected worker is unauthorized`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    fleetStore.rejectWorker(workerId)

    val response = claim("${workerId.value}.$secret", "some-profile")
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `revoked worker is unauthorized`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    fleetStore.revokeWorker(workerId)

    val response = claim("${workerId.value}.$secret", "some-profile")
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `wrong secret is unauthorized`() {
    val (workerId, _) = registerActiveWorker()

    val response = claim("${workerId.value}.wrong-secret", "some-profile")
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `unknown worker is unauthorized`() {
    val response = claim("${UUID.randomUUID()}.some-secret", "some-profile")
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `malformed bearer token is unauthorized`() {
    val response = claim("not-a-valid-token", "some-profile")
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `the retired bootstrap-token bearer shape gets a plain 401, not special-cased`() {
    // Shaped like the old single shared bootstrap token (openssl rand -base64 32): opaque,
    // no "workerId.secret" separator. It must fail exactly like any other malformed bearer
    // value — no legacy code path recognizes it.
    val legacyShapedToken = "Zm9vYmFyYmF6cXV1eGNvcmdlZ3JhdWx0Z2FyYnBseXdhbGRvZmxlZWJhdGh6b3Q="
    val response = claim(legacyShapedToken, "some-profile")

    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
    assertEquals(
        WorkerErrorResponse("unauthorized"),
        workerJson.decodeFromString<WorkerErrorResponse>(response.contentUtf8()),
    )
  }

  @Test
  fun `missing profileId in body is a bad request`() {
    val (workerId, secret) = registerActiveWorker()

    val response = claim("${workerId.value}.$secret", null)
    assertEquals(HttpStatus.BAD_REQUEST, response.status())
  }

  @Test
  fun `active worker without a grant gets 403 naming the condition`() {
    val (workerId, secret) = registerActiveWorker()

    val response = claim("${workerId.value}.$secret", "never-granted")
    assertEquals(HttpStatus.FORBIDDEN, response.status())
    assertEquals(
        WorkerErrorResponse("profile_not_found"),
        workerJson.decodeFromString<WorkerErrorResponse>(response.contentUtf8()),
    )
  }

  @Test
  fun `archived profile gets 403 naming the condition`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId)
    fleetStore.archiveProfile(profileId)

    val response = claim("${workerId.value}.$secret", profileId.value)
    assertEquals(HttpStatus.FORBIDDEN, response.status())
    assertEquals(
        WorkerErrorResponse("profile_archived"),
        workerJson.decodeFromString<WorkerErrorResponse>(response.contentUtf8()),
    )
  }

  @Test
  fun `ungranted profile gets 403 naming the condition`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    val otherWorkerId = registerActiveWorker(name = "worker-2").first
    val profileId = createGrantedProfile(otherWorkerId)

    val response = claim("${workerId.value}.$secret", profileId.value)
    assertEquals(HttpStatus.FORBIDDEN, response.status())
    assertEquals(
        WorkerErrorResponse("not_granted"),
        workerJson.decodeFromString<WorkerErrorResponse>(response.contentUtf8()),
    )
  }

  @Test
  fun `after UpdateProfile the next claim uses the new revision`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId, "old-sa@example.iam.gserviceaccount.com")

    fleetStore.appendProfileRevision(
        profileId,
        NewProfileRevision(
            "new-sa@example.iam.gserviceaccount.com",
            createdBy = "admin@example.com",
        ),
    )
    fleetStore.recordVerification(profileId, revision = 2, VerificationStatus.VERIFIED)

    val response = claim("${workerId.value}.$secret", profileId.value)
    assertEquals(HttpStatus.OK, response.status())
    val body = workerJson.decodeFromString<WorkerTokenResponse>(response.contentUtf8())
    assertEquals(2, body.revision)
    assertEquals("new-sa@example.iam.gserviceaccount.com", body.serviceAccount)
  }

  @Test
  fun `an unverified revision is not claimable`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    val profileId = ProfileId("profile-${UUID.randomUUID()}")
    fleetStore.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                "target@example.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
            ),
    )
    fleetStore.grant(workerId, profileId, grantedBy = "admin@example.com")
    // Left UNVERIFIED — never went through FleetServiceImpl's synchronous verifyAndRecord.

    val response = claim("${workerId.value}.$secret", profileId.value)
    assertEquals(HttpStatus.FORBIDDEN, response.status())
    assertEquals(
        WorkerErrorResponse("not_verified"),
        workerJson.decodeFromString<WorkerErrorResponse>(response.contentUtf8()),
    )
  }

  @Test
  fun `a revision flagged binding_missing is not claimable`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId)
    fleetStore.recordVerification(profileId, revision = 1, VerificationStatus.BINDING_MISSING)

    val response = claim("${workerId.value}.$secret", profileId.value)
    assertEquals(HttpStatus.FORBIDDEN, response.status())
    assertEquals(
        WorkerErrorResponse("not_verified"),
        workerJson.decodeFromString<WorkerErrorResponse>(response.contentUtf8()),
    )
  }

  @Test
  fun `a revision flagged secret_inaccessible is not claimable`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId)
    fleetStore.recordVerification(profileId, revision = 1, VerificationStatus.SECRET_INACCESSIBLE)

    val response = claim("${workerId.value}.$secret", profileId.value)
    assertEquals(HttpStatus.FORBIDDEN, response.status())
    assertEquals(
        WorkerErrorResponse("not_verified"),
        workerJson.decodeFromString<WorkerErrorResponse>(response.contentUtf8()),
    )
  }

  @Test
  fun `claiming a token updates the worker's lastSeenAt`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId)

    claim("${workerId.value}.$secret", profileId.value)

    kotlin.test.assertNotNull(fleetStore.getWorker(workerId)?.lastSeenAt)
  }
}
