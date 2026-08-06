package software.medusa.workload.server

import com.linecorp.armeria.client.WebClient
import com.linecorp.armeria.common.AggregatedHttpResponse
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.RequestHeaders
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import software.medusa.workload.tokenformat.TokenKind
import software.medusa.workload.tokenformat.WorkloadToken

/**
 * Mounts the three run routes bare (no decorators), the same way [buildWorkerServiceTestServer]
 * does for single-route services — so the handler's own status semantics (200/204/400/401/403/404)
 * are asserted un-rewritten. The credential-drop's 401→404 unification is covered separately in
 * [WorkerPlaneV2UnprobeableTest].
 */
private fun buildRunTestServer(service: HttpService): Server =
    Server.builder()
        .apply {
          http(0)
          route().methods(HttpMethod.POST).path("/worker/v2/runs").build(service)
          route().methods(HttpMethod.POST).path("/worker/v2/runs/{runId}/heartbeat").build(service)
          route().methods(HttpMethod.POST).path("/worker/v2/runs/{runId}/end").build(service)
        }
        .build()

/** End-to-end check of [WorkerRunService] via a real running server. */
class WorkerRunServiceTest {

  private lateinit var fleetStore: FleetStore
  private lateinit var server: Server
  private lateinit var client: WebClient

  @BeforeTest
  fun start() {
    fleetStore = InMemoryFleetStore()
    server = buildRunTestServer(WorkerRunService(fleetStore))
    server.start().join()
    client = WebClient.of("http://127.0.0.1:${server.activeLocalPort()}")
  }

  @AfterTest
  fun stop() {
    server.stop().join()
  }

  private fun post(path: String, bearer: String?, body: String = ""): AggregatedHttpResponse {
    val headers =
        if (bearer == null)
            RequestHeaders.of(HttpMethod.POST, path, "Content-Type", "application/json")
        else
            RequestHeaders.of(
                HttpMethod.POST,
                path,
                "Authorization",
                "Bearer $bearer",
                "Content-Type",
                "application/json",
            )
    return client.execute(headers, body).aggregate().join()
  }

  private fun registerActiveWorker(name: String = "run-worker"): Pair<WorkerId, String> =
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

  private fun createRunBody(kind: String = "run", profileId: String = "run-test-profile") =
      workerJson.encodeToString(
          CreateRunRequest(profileId = profileId, revision = 1, kind = kind, imageDigest = null)
      )

  @Test
  fun `create records a running run and returns the id plus heartbeat cadence`() {
    val (workerId, secret) = registerActiveWorker()

    val response = post("/worker/v2/runs", bearer(workerId, secret), createRunBody())

    assertEquals(HttpStatus.OK, response.status())
    val body = workerJson.decodeFromString<CreateRunResponse>(response.contentUtf8())
    assertEquals(heartbeatInterval.seconds, body.heartbeatIntervalSeconds)

    val run = runBlocking { fleetStore.getRun(RunId(UUID.fromString(body.runId))) }
    assertNotNull(run)
    assertEquals(RunState.RUNNING, run.state)
    assertEquals(workerId, run.workerId)
    assertEquals("run-test-profile", run.profileId?.value)
    assertEquals(RunKind.RUN, run.kind)
    // Creating a run marks the worker seen.
    assertNotNull(runBlocking { fleetStore.getWorker(workerId) }?.lastSeenAt)
  }

  @Test
  fun `create with an unknown kind is a bad request`() {
    val (workerId, secret) = registerActiveWorker()
    val response = post("/worker/v2/runs", bearer(workerId, secret), createRunBody(kind = "bogus"))
    assertEquals(HttpStatus.BAD_REQUEST, response.status())
    assertEquals(
        WorkerErrorResponse("invalid_kind"),
        workerJson.decodeFromString(response.contentUtf8()),
    )
  }

  @Test
  fun `create with kind agent needs no profile or revision`() {
    val (workerId, secret) = registerActiveWorker()
    val body =
        workerJson.encodeToString(
            CreateRunRequest(profileId = null, revision = null, kind = "agent", imageDigest = null)
        )

    val response = post("/worker/v2/runs", bearer(workerId, secret), body)

    assertEquals(HttpStatus.OK, response.status())
    val created = workerJson.decodeFromString<CreateRunResponse>(response.contentUtf8())
    val run = runBlocking { fleetStore.getRun(RunId(UUID.fromString(created.runId))) }
    assertNotNull(run)
    assertEquals(RunKind.AGENT, run.kind)
    assertNull(run.profileId)
    assertNull(run.revision)
  }

  @Test
  fun `a malformed bearer is unauthorized`() {
    val response = post("/worker/v2/runs", "not-a-valid-token", createRunBody())
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  @Test
  fun `a non-active worker is unauthorized`() = runBlocking {
    val secret = WorkloadToken.generate(TokenKind.WORKER)
    val worker =
        fleetStore.createWorker(
            NewWorker(
                hashWorkerSecret(secret),
                "pending",
                hostname = null,
                os = null,
                cliVersion = null,
            )
        )
    val response = post("/worker/v2/runs", bearer(worker.workerId, secret), createRunBody())
    assertEquals(HttpStatus.UNAUTHORIZED, response.status())
  }

  // Real clock so the fresh run reads RUNNING (a fixed past start would derive LOST against now).
  private fun startRun(workerId: WorkerId, kind: RunKind = RunKind.RUN): RunId = runBlocking {
    fleetStore
        .createRun(NewRun(workerId, ProfileId("run-test-profile"), revision = 1, kind = kind))
        .runId
  }

  @Test
  fun `heartbeat on an owned run is 204 and refreshes the heartbeat and last-seen`() {
    val (workerId, secret) = registerActiveWorker()
    val runId = startRun(workerId)

    val response = post("/worker/v2/runs/${runId.value}/heartbeat", bearer(workerId, secret))
    assertEquals(HttpStatus.NO_CONTENT, response.status())

    val run = runBlocking { fleetStore.getRun(runId) }
    // The heartbeat moved forward from the fixed start time.
    assertEquals(true, run!!.lastHeartbeatAt.isAfter(run.startedAt))
    assertNotNull(runBlocking { fleetStore.getWorker(workerId) }?.lastSeenAt)
  }

  @Test
  fun `heartbeat on another worker's run is forbidden`() {
    val (owner, _) = registerActiveWorker("owner")
    val (intruder, intruderSecret) = registerActiveWorker("intruder")
    val runId = startRun(owner)

    val response =
        post("/worker/v2/runs/${runId.value}/heartbeat", bearer(intruder, intruderSecret))
    assertEquals(HttpStatus.FORBIDDEN, response.status())
    assertEquals(
        WorkerErrorResponse("not_your_run"),
        workerJson.decodeFromString(response.contentUtf8()),
    )
  }

  @Test
  fun `heartbeat on an unknown run is not found`() {
    val (workerId, secret) = registerActiveWorker()
    val response = post("/worker/v2/runs/${UUID.randomUUID()}/heartbeat", bearer(workerId, secret))
    assertEquals(HttpStatus.NOT_FOUND, response.status())
    assertEquals(
        WorkerErrorResponse("run_not_found"),
        workerJson.decodeFromString(response.contentUtf8()),
    )
  }

  @Test
  fun `end with exit zero records success, nonzero records failure`() {
    val (workerId, secret) = registerActiveWorker()
    val ok = startRun(workerId)
    val bad = startRun(workerId)

    assertEquals(
        HttpStatus.NO_CONTENT,
        post("/worker/v2/runs/${ok.value}/end", bearer(workerId, secret), """{"exitCode":0}""")
            .status(),
    )
    assertEquals(RunState.SUCCEEDED, runBlocking { fleetStore.getRun(ok) }?.state)
    assertEquals(0, runBlocking { fleetStore.getRun(ok) }?.exitCode)

    assertEquals(
        HttpStatus.NO_CONTENT,
        post("/worker/v2/runs/${bad.value}/end", bearer(workerId, secret), """{"exitCode":9}""")
            .status(),
    )
    assertEquals(RunState.FAILED, runBlocking { fleetStore.getRun(bad) }?.state)
  }

  @Test
  fun `end with no body records a failure with an unknown exit code`() {
    val (workerId, secret) = registerActiveWorker()
    val runId = startRun(workerId)

    val response = post("/worker/v2/runs/${runId.value}/end", bearer(workerId, secret))
    assertEquals(HttpStatus.NO_CONTENT, response.status())
    val run = runBlocking { fleetStore.getRun(runId) }
    assertEquals(RunState.FAILED, run?.state)
    assertNull(run?.exitCode)
  }

  @Test
  fun `a second end is idempotent and leaves the first terminal state`() {
    val (workerId, secret) = registerActiveWorker()
    val runId = startRun(workerId)

    post("/worker/v2/runs/${runId.value}/end", bearer(workerId, secret), """{"exitCode":0}""")
    val second =
        post("/worker/v2/runs/${runId.value}/end", bearer(workerId, secret), """{"exitCode":1}""")

    assertEquals(HttpStatus.NO_CONTENT, second.status())
    assertEquals(RunState.SUCCEEDED, runBlocking { fleetStore.getRun(runId) }?.state)
    assertEquals(0, runBlocking { fleetStore.getRun(runId) }?.exitCode)
  }

  @Test
  fun `end on another worker's run is forbidden`() {
    val (owner, _) = registerActiveWorker("owner")
    val (intruder, intruderSecret) = registerActiveWorker("intruder")
    val runId = startRun(owner)

    val response =
        post(
            "/worker/v2/runs/${runId.value}/end",
            bearer(intruder, intruderSecret),
            """{"exitCode":0}""",
        )
    assertEquals(HttpStatus.FORBIDDEN, response.status())
    assertEquals(RunState.RUNNING, runBlocking { fleetStore.getRun(runId) }?.state)
  }
}
