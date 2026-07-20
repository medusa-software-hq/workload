package software.medusa.workload.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.Server
import java.util.Base64
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import software.medusa.workload.tokenformat.TokenKind
import software.medusa.workload.tokenformat.WorkloadToken

private const val idTokenPath = "/worker/id-token"

/**
 * ID-token-specific behavior of [WorkerIdTokenBrokerService]. The auth/grant/revision denial paths
 * come from the shared [WorkerClaimResolver] and are covered by [WorkerTokenBrokerServiceTest];
 * this exercises what's new: audience is required, it round-trips into the minted token, and
 * includeEmail is honored.
 */
class WorkerIdTokenBrokerServiceTest {

  private lateinit var fleetStore: FleetStore
  private lateinit var server: Server
  private lateinit var client: WebClient

  @BeforeTest
  fun start() {
    fleetStore = InMemoryFleetStore()
    server =
        buildWorkerServiceTestServer(
            idTokenPath,
            WorkerIdTokenBrokerService(fleetStore, FakeTokenMinter),
        )
    server.start().join()
    client = WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }

  @AfterTest
  fun stop() {
    server.stop().join()
  }

  private fun claimIdToken(bearer: String, body: String): AggregatedHttpResponse =
      client
          .execute(
              RequestHeaders.of(
                  HttpMethod.POST,
                  idTokenPath,
                  "Authorization",
                  "Bearer $bearer",
                  "Content-Type",
                  "application/json",
              ),
              body,
          )
          .aggregate()
          .join()

  private fun registerActiveWorker(): Pair<WorkerId, String> = runBlocking {
    val secret = WorkloadToken.generate(TokenKind.WORKER)
    val worker =
        fleetStore.createWorker(
            NewWorker(
                secretHash = hashWorkerSecret(secret),
                name = "worker-1",
                hostname = null,
                os = null,
                cliVersion = null,
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
    fleetStore.recordVerification(profileId, revision = 1, VerificationStatus.VERIFIED)
    fleetStore.grant(workerId, profileId, grantedBy = "admin@example.com")
    profileId
  }

  /** The decoded payload JSON of a (possibly unsigned) JWT `header.payload.sig`. */
  private fun payloadOf(jwt: String): String =
      String(Base64.getUrlDecoder().decode(jwt.split(".")[1]))

  @Test
  fun `granted worker gets an audience-bound id token naming the profile, revision, and SA`() {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId, "target@example.iam.gserviceaccount.com")

    val response =
        claimIdToken(
            "${workerId.value}.$secret",
            workerJson.encodeToString(
                TokenClaimRequest(profileId.value, audience = "https://flow.example/api")
            ),
        )

    assertEquals(HttpStatus.OK, response.status())
    val body = workerJson.decodeFromString<WorkerIdTokenResponse>(response.contentUtf8())
    assertEquals(profileId.value, body.profileId)
    assertEquals(1, body.revision)
    assertEquals("target@example.iam.gserviceaccount.com", body.serviceAccount)
    assertEquals("https://flow.example/api", body.audience)
    // The audience the caller asked for is really the `aud` claim of the minted token.
    assertTrue("\"aud\":\"https://flow.example/api\"" in payloadOf(body.idToken))
  }

  @Test
  fun `a claim with no audience is a bad request`() {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId)

    val response =
        claimIdToken(
            "${workerId.value}.$secret",
            workerJson.encodeToString(TokenClaimRequest(profileId.value)),
        )

    assertEquals(HttpStatus.BAD_REQUEST, response.status())
    assertEquals(
        WorkerErrorResponse("invalid_audience"),
        workerJson.decodeFromString<WorkerErrorResponse>(response.contentUtf8()),
    )
  }

  @Test
  fun `a blank audience is a bad request`() {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId)

    val response =
        claimIdToken(
            "${workerId.value}.$secret",
            workerJson.encodeToString(TokenClaimRequest(profileId.value, audience = "  ")),
        )
    assertEquals(HttpStatus.BAD_REQUEST, response.status())
  }

  // includeEmail split into two tests (one request each) — the rate limiter throttles a second
  // rapid claim against the same fresh server.

  @Test
  fun `includeEmail true embeds the SA email claim`() {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId, "svc@example.iam.gserviceaccount.com")

    val response =
        claimIdToken(
            "${workerId.value}.$secret",
            workerJson.encodeToString(
                TokenClaimRequest(profileId.value, audience = "aud", includeEmail = true)
            ),
        )
    val body = workerJson.decodeFromString<WorkerIdTokenResponse>(response.contentUtf8())
    assertTrue("\"email\":\"svc@example.iam.gserviceaccount.com\"" in payloadOf(body.idToken))
  }

  @Test
  fun `without includeEmail the token carries no email claim`() {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId, "svc@example.iam.gserviceaccount.com")

    val response =
        claimIdToken(
            "${workerId.value}.$secret",
            workerJson.encodeToString(TokenClaimRequest(profileId.value, audience = "aud")),
        )
    val body = workerJson.decodeFromString<WorkerIdTokenResponse>(response.contentUtf8())
    assertFalse("email" in payloadOf(body.idToken))
  }
}
