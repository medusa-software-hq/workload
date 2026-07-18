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
private const val claimPath = "/$prefix/worker/v1/claim"

/** End-to-end check of [WorkerClaimService] via a real running server. */
class WorkerClaimServiceTest {

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
            fleetStore = fleetStore,
            impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
            imageDigestResolver = AlwaysResolvedImageDigestResolver,
            workerClaimService = WorkerClaimService(fleetStore, FakeTokenMinter),
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
                claimPath,
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
      envVars: Map<String, String> = emptyMap(),
      secretEnvVars: Map<String, String> = emptyMap(),
  ): ProfileId = runBlocking {
    val profileId = ProfileId("profile-${UUID.randomUUID()}")
    fleetStore.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                targetServiceAccount,
                createdBy = "admin@example.com",
                envVars = envVars,
                secretEnvVars = secretEnvVars,
            ),
    )
    // FleetServiceImpl.createProfile always verifies synchronously; do the same here so a
    // freshly created test profile is claimable, same as it would be in production.
    fleetStore.recordVerification(profileId, revision = 1, VerificationStatus.VERIFIED)
    fleetStore.grant(workerId, profileId, grantedBy = "admin@example.com")
    profileId
  }

  @Test
  fun `claim returns the token plus the full env payload for a verified revision`() {
    val (workerId, secret) = registerActiveWorker()
    val profileId =
        createGrantedProfile(
            workerId,
            targetServiceAccount = "target@example.iam.gserviceaccount.com",
            envVars = mapOf("MODE" to "batch"),
            secretEnvVars = mapOf("API_KEY" to "projects/p/secrets/api-key/versions/latest"),
        )

    val response = claim("${workerId.value}.$secret", profileId.value)

    assertEquals(HttpStatus.OK, response.status())
    val body = workerJson.decodeFromString<WorkerClaimResponse>(response.contentUtf8())
    assertEquals(profileId.value, body.profileId)
    assertEquals(1, body.revision)
    assertEquals("target@example.iam.gserviceaccount.com", body.serviceAccount)
    assertEquals("fake-access-token", body.accessToken)
    assertEquals(mapOf("MODE" to "batch"), body.envVars)
    // Only the resource reference crosses the broker, never a resolved secret value.
    assertEquals(
        mapOf("API_KEY" to "projects/p/secrets/api-key/versions/latest"),
        body.secretEnvVars,
    )
  }

  @Test
  fun `claim returns empty maps for a revision with no env vars`() {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId)

    val response = claim("${workerId.value}.$secret", profileId.value)

    assertEquals(HttpStatus.OK, response.status())
    val body = workerJson.decodeFromString<WorkerClaimResponse>(response.contentUtf8())
    assertEquals(emptyMap(), body.envVars)
    assertEquals(emptyMap(), body.secretEnvVars)
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
  fun `wrong secret is unauthorized`() {
    val (workerId, _) = registerActiveWorker()

    val response = claim("${workerId.value}.wrong-secret", "some-profile")
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `malformed bearer token is unauthorized`() {
    val response = claim("not-a-valid-token", "some-profile")
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
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
  fun `a revision flagged binding_missing is not claimable, error names the flag`() = runBlocking {
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
  fun `a revision flagged secret_inaccessible is not claimable, error names the flag`() =
      runBlocking {
        val (workerId, secret) = registerActiveWorker()
        val profileId = createGrantedProfile(workerId)
        fleetStore.recordVerification(
            profileId,
            revision = 1,
            VerificationStatus.SECRET_INACCESSIBLE,
        )

        val response = claim("${workerId.value}.$secret", profileId.value)
        assertEquals(HttpStatus.FORBIDDEN, response.status())
        assertEquals(
            WorkerErrorResponse("not_verified"),
            workerJson.decodeFromString<WorkerErrorResponse>(response.contentUtf8()),
        )
      }

  @Test
  fun `after UpdateProfile the next claim uses the new revision's env payload`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    val profileId =
        createGrantedProfile(
            workerId,
            targetServiceAccount = "old-sa@example.iam.gserviceaccount.com",
            envVars = mapOf("MODE" to "batch"),
        )

    fleetStore.appendProfileRevision(
        profileId,
        NewProfileRevision(
            "new-sa@example.iam.gserviceaccount.com",
            createdBy = "admin@example.com",
            envVars = mapOf("MODE" to "streaming"),
        ),
    )
    fleetStore.recordVerification(profileId, revision = 2, VerificationStatus.VERIFIED)

    val response = claim("${workerId.value}.$secret", profileId.value)
    assertEquals(HttpStatus.OK, response.status())
    val body = workerJson.decodeFromString<WorkerClaimResponse>(response.contentUtf8())
    assertEquals(2, body.revision)
    assertEquals("new-sa@example.iam.gserviceaccount.com", body.serviceAccount)
    assertEquals(mapOf("MODE" to "streaming"), body.envVars)
  }

  @Test
  fun `claim payload carries ref and digest for an image profile`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    val profileId = ProfileId("profile-image-${UUID.randomUUID()}")
    fleetStore.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                "target@example.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
                dockerImage = "us-docker.pkg.dev/p/repo/app:v1",
            ),
    )
    fleetStore.recordVerification(profileId, revision = 1, VerificationStatus.VERIFIED)
    fleetStore.recordImageDigest(profileId, revision = 1, "sha256:abc", ImageStatus.RESOLVED)
    fleetStore.grant(workerId, profileId, grantedBy = "admin@example.com")

    val response = claim("${workerId.value}.$secret", profileId.value)
    assertEquals(HttpStatus.OK, response.status())
    val body = workerJson.decodeFromString<WorkerClaimResponse>(response.contentUtf8())
    assertEquals(ClaimImage("us-docker.pkg.dev/p/repo/app:v1", "sha256:abc"), body.image)
  }

  @Test
  fun `claim payload has null image for a pure exec profile`() {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId)

    val response = claim("${workerId.value}.$secret", profileId.value)
    assertEquals(HttpStatus.OK, response.status())
    val body = workerJson.decodeFromString<WorkerClaimResponse>(response.contentUtf8())
    kotlin.test.assertNull(body.image)
  }

  @Test
  fun `an unresolvable image is not claimable, error names image_unresolvable`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    val profileId = ProfileId("profile-image-bad-${UUID.randomUUID()}")
    fleetStore.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                "target@example.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
                dockerImage = "us-docker.pkg.dev/p/repo/missing:v9",
            ),
    )
    // Verified SA, but the image digest never pinned — a distinct gate from verification.
    fleetStore.recordVerification(profileId, revision = 1, VerificationStatus.VERIFIED)
    fleetStore.recordImageDigest(profileId, revision = 1, null, ImageStatus.UNRESOLVABLE)
    fleetStore.grant(workerId, profileId, grantedBy = "admin@example.com")

    val response = claim("${workerId.value}.$secret", profileId.value)
    assertEquals(HttpStatus.FORBIDDEN, response.status())
    assertEquals(
        WorkerErrorResponse("image_unresolvable"),
        workerJson.decodeFromString<WorkerErrorResponse>(response.contentUtf8()),
    )
  }

  @Test
  fun `claiming updates the worker's lastSeenAt`() = runBlocking {
    val (workerId, secret) = registerActiveWorker()
    val profileId = createGrantedProfile(workerId)

    claim("${workerId.value}.$secret", profileId.value)

    kotlin.test.assertNotNull(fleetStore.getWorker(workerId)?.lastSeenAt)
  }
}
