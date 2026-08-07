package software.medusa.workload.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/** One profile's point-in-time reconcile status, as `workload-agent` last computed it. */
@Serializable
internal data class AgentAssignmentStatusReport(
    val profileId: String,
    val runningDigest: String? = null,
    val desiredDigest: String? = null,
    // "converged" | "draining" | "crashloop_hold" | "replacing".
    val state: String,
    val since: String,
    val drainDeadline: String? = null,
)

@Serializable
internal data class ReportAssignmentStatusRequest(val statuses: List<AgentAssignmentStatusReport>)

/**
 * Implements `POST /worker/v2/status` (M7 automated rollout): the counterpart to
 * [WorkerAssignmentsService] — instead of the broker telling the agent what to run, this is the
 * agent telling the broker what it's actually doing with it. Each report replaces the worker's
 * [Worker.assignmentStatuses] wholesale (see [FleetStore.updateAssignmentStatuses]); it's a
 * point-in-time snapshot, not audit history — `admin workers list`'s digest/drift/state columns are
 * only ever as fresh as the last successful report.
 *
 * Worker-authenticated the same way as [WorkerAssignmentsService] (`Bearer <workerId>.<secret>`),
 * so it rides the same `v2WorkerCredentialDrop` decorator and stays part of the unprobeable v2
 * plane. A malformed individual status entry (bad state name, unparseable timestamp) is dropped
 * rather than failing the whole report — one bad entry must never blind the console to every other
 * profile on the node.
 */
class WorkerStatusReportService(
    private val fleetStore: FleetStore,
) : HttpService {
  override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse =
      HttpResponse.of({ runBlocking { handle(req) } }, ctx.blockingTaskExecutor())

  private suspend fun handle(req: HttpRequest): HttpResponse {
    val worker = authenticate(req) ?: return unauthorized()
    fleetStore.touchLastSeen(worker.workerId)

    val body = req.aggregate().join().contentUtf8()
    val request =
        runCatching { workerJson.decodeFromString<ReportAssignmentStatusRequest>(body) }
            .getOrElse {
              return badRequest()
            }

    val statuses = request.statuses.mapNotNull { it.toDomain() }
    fleetStore.updateAssignmentStatuses(worker.workerId, statuses)

    return HttpResponse.of(HttpStatus.NO_CONTENT)
  }

  private fun AgentAssignmentStatusReport.toDomain(): AgentAssignmentStatus? =
      runCatching {
            AgentAssignmentStatus(
                profileId = ProfileId(profileId),
                runningDigest = runningDigest?.takeIf { it.isNotBlank() },
                desiredDigest = desiredDigest?.takeIf { it.isNotBlank() },
                state = AgentAssignmentState.valueOf(state.uppercase()),
                since = Instant.parse(since),
                drainDeadline = drainDeadline?.takeIf { it.isNotBlank() }?.let(Instant::parse),
            )
          }
          .getOrNull()

  private suspend fun authenticate(req: HttpRequest): Worker? {
    val (workerId, secret) =
        extractBearerToken(req)?.let { parseWorkerBearerToken(it) } ?: return null
    val worker = fleetStore.getWorker(workerId) ?: return null
    if (!verifyWorkerSecret(secret, worker.secretHash)) return null
    if (worker.status != WorkerStatus.ACTIVE) return null
    return worker
  }

  private fun unauthorized(): HttpResponse =
      jsonResponse(HttpStatus.UNAUTHORIZED, WorkerErrorResponse("unauthorized"))

  private fun badRequest(): HttpResponse =
      jsonResponse(HttpStatus.BAD_REQUEST, WorkerErrorResponse("malformed_request"))

  private fun jsonResponse(status: HttpStatus, body: WorkerErrorResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))
}
