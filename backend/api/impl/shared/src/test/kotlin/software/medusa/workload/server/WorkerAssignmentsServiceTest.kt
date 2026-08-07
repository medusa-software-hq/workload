package software.medusa.workload.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import software.medusa.workload.tokenformat.TokenKind
import software.medusa.workload.tokenformat.WorkloadToken

private fun buildAssignmentsTestServer(service: HttpService): Server =
    Server.builder()
        .apply {
          http(0)
          route().methods(HttpMethod.GET).path("/worker/v2/assignments").build(service)
        }
        .build()

/** End-to-end check of [WorkerAssignmentsService] via a real running server. */
class WorkerAssignmentsServiceTest {

  private lateinit var fleetStore: FleetStore
  private lateinit var server: Server
  private lateinit var client: WebClient

  @BeforeTest
  fun start() {
    fleetStore = InMemoryFleetStore()
    server = buildAssignmentsTestServer(WorkerAssignmentsService(fleetStore))
    server.start().join()
    client = WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }

  @AfterTest
  fun stop() {
    server.stop().join()
  }

  private fun get(bearer: String?): AggregatedHttpResponse {
    val headers =
        if (bearer == null) RequestHeaders.of(HttpMethod.GET, "/worker/v2/assignments")
        else
            RequestHeaders.of(
                HttpMethod.GET,
                "/worker/v2/assignments",
                "Authorization",
                "Bearer $bearer",
            )
    return client.execute(headers).aggregate().join()
  }

  private fun registerActiveWorker(name: String = "assignments-worker"): Pair<WorkerId, String> =
      runBlocking {
        val secret = WorkloadToken.generate(TokenKind.WORKER)
        val worker =
            fleetStore.createWorker(
                NewWorker(
                    hashWorkerSecret(secret),
                    name,
                    hostname = null,
                    os = null,
                    cliVersion = null,
                )
            )
        fleetStore.approveWorker(worker.workerId, approvedBy = "admin@example.com")
        worker.workerId to secret
      }

  private fun bearer(workerId: WorkerId, secret: String) = "${workerId.value}.$secret"

  private suspend fun grantImageProfile(
      workerId: WorkerId,
      profileId: String,
      resolved: Boolean = true,
  ) {
    val id = ProfileId(profileId)
    fleetStore.createProfile(
        id,
        displayName = null,
        revision =
            NewProfileRevision(
                targetServiceAccount = "sa@example.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
                dockerImage = "us-docker.pkg.dev/p/repo/$profileId:v1",
            ),
    )
    if (resolved) {
      fleetStore.recordImageDigest(
          id,
          revision = 1,
          digest = "sha256:deadbeef",
          ImageStatus.RESOLVED,
      )
    }
    fleetStore.grant(workerId, id, grantedBy = "admin@example.com")
  }

  @Test
  fun `lists granted profiles with a resolved image, paused reflects the worker`() {
    val (workerId, secret) = registerActiveWorker()
    runBlocking { grantImageProfile(workerId, "img-profile") }

    val response = get(bearer(workerId, secret))
    assertEquals(HttpStatus.OK, response.status())
    val body = workerJson.decodeFromString<AgentAssignmentsResponse>(response.contentUtf8())

    assertEquals(1, body.assignments.size)
    assertEquals("img-profile", body.assignments.single().profileId)
    assertEquals("sha256:deadbeef", body.assignments.single().dockerImageDigest)
    assertEquals(false, body.paused)
  }

  @Test
  fun `omits a granted profile with no resolved image`() {
    val (workerId, secret) = registerActiveWorker()
    runBlocking { grantImageProfile(workerId, "unresolved-profile", resolved = false) }

    val response = get(bearer(workerId, secret))
    val body = workerJson.decodeFromString<AgentAssignmentsResponse>(response.contentUtf8())
    assertTrue(body.assignments.isEmpty())
  }

  @Test
  fun `reflects a paused worker`() {
    val (workerId, secret) = registerActiveWorker()
    runBlocking {
      grantImageProfile(workerId, "img-profile")
      fleetStore.setWorkerPaused(workerId, paused = true)
    }

    val body =
        workerJson.decodeFromString<AgentAssignmentsResponse>(
            get(bearer(workerId, secret)).contentUtf8()
        )
    assertTrue(body.paused)
    // Still reports the assignment set even while paused — it's the agent's job to reconcile to
    // empty, not the server's.
    assertEquals(1, body.assignments.size)
  }

  @Test
  fun `a malformed bearer is unauthorized`() {
    assertEquals(HttpStatus.UNAUTHORIZED, get("not-a-valid-token").status())
  }

  @Test
  fun `a fallback-eligible profile with no dedicated node lands on the fallback worker`() {
    val (fallbackWorkerId, fallbackSecret) = registerActiveWorker("fallback-worker")
    val reconciler = FallbackPlacementReconciler(fleetStore)
    runBlocking {
      fleetStore.setWorkerFallbackNode(fallbackWorkerId, fallbackNode = true)
      val profile =
          fleetStore.createProfile(
              ProfileId("fallback-profile"),
              displayName = null,
              revision =
                  NewProfileRevision(
                      targetServiceAccount = "sa@example.iam.gserviceaccount.com",
                      createdBy = "admin@example.com",
                      dockerImage = "us-docker.pkg.dev/p/repo/fallback-profile:v1",
                  ),
          )
      fleetStore.recordImageDigest(
          profile.profileId,
          revision = 1,
          digest = "sha256:deadbeef",
          ImageStatus.RESOLVED,
      )
      fleetStore.setProfileFallbackEligible(profile.profileId, fallbackEligible = true)
      reconciler.reconcile(profile.profileId)
    }

    val body =
        workerJson.decodeFromString<AgentAssignmentsResponse>(
            get(bearer(fallbackWorkerId, fallbackSecret)).contentUtf8()
        )
    assertEquals(1, body.assignments.size)
    assertEquals("fallback-profile", body.assignments.single().profileId)
  }

  @Test
  fun `it migrates off the fallback worker once a dedicated worker is granted the profile`() {
    val (fallbackWorkerId, fallbackSecret) = registerActiveWorker("fallback-worker-2")
    val (dedicatedWorkerId, dedicatedSecret) = registerActiveWorker("dedicated-worker")
    val reconciler = FallbackPlacementReconciler(fleetStore)
    val profileId = ProfileId("fallback-profile-2")
    runBlocking {
      fleetStore.setWorkerFallbackNode(fallbackWorkerId, fallbackNode = true)
      fleetStore.createProfile(
          profileId,
          displayName = null,
          revision =
              NewProfileRevision(
                  targetServiceAccount = "sa@example.iam.gserviceaccount.com",
                  createdBy = "admin@example.com",
                  dockerImage = "us-docker.pkg.dev/p/repo/fallback-profile-2:v1",
              ),
      )
      fleetStore.recordImageDigest(
          profileId,
          revision = 1,
          digest = "sha256:deadbeef",
          ImageStatus.RESOLVED,
      )
      fleetStore.setProfileFallbackEligible(profileId, fallbackEligible = true)
      reconciler.reconcile(profileId)
    }
    assertEquals(
        1,
        workerJson
            .decodeFromString<AgentAssignmentsResponse>(
                get(bearer(fallbackWorkerId, fallbackSecret)).contentUtf8()
            )
            .assignments
            .size,
    )

    // A dedicated node appears — an admin grants the profile to a real worker.
    runBlocking {
      fleetStore.grant(dedicatedWorkerId, profileId, grantedBy = "admin@example.com")
      reconciler.reconcile(profileId)
    }

    val fallbackBody =
        workerJson.decodeFromString<AgentAssignmentsResponse>(
            get(bearer(fallbackWorkerId, fallbackSecret)).contentUtf8()
        )
    assertTrue(fallbackBody.assignments.isEmpty())
    val dedicatedBody =
        workerJson.decodeFromString<AgentAssignmentsResponse>(
            get(bearer(dedicatedWorkerId, dedicatedSecret)).contentUtf8()
        )
    assertEquals(1, dedicatedBody.assignments.size)
  }
}
