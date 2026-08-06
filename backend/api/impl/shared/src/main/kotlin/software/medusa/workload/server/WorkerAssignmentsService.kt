package software.medusa.workload.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

/**
 * One profile `workload-agent` should keep running continuously on this node. Named
 * `AgentAssignment` (not `Assignment`) to stay distinct from
 * [software.medusa.workload.v1.Assignment], the first-class admin-managed placement record — this
 * type is this endpoint's derived, unrelated wire shape.
 */
@Serializable
internal data class AgentAssignment(
    val profileId: String,
    val revision: Int,
    val dockerImage: String,
    val dockerImageDigest: String,
)

@Serializable
internal data class AgentAssignmentsResponse(
    val assignments: List<AgentAssignment>,
    // The operator pause/serve switch (M7-05): while true, the agent should reconcile to an empty
    // set regardless of [assignments] — a drain, not a change to what's granted.
    val paused: Boolean,
)

/**
 * Implements `GET /worker/v2/assignments` (M7-05): the node's "assignment set" a `workload-agent`
 * daemon reconciles its running containers against. Derived, never stored — exactly the granted
 * profiles ([FleetStore.listGrantedProfileIds]) whose latest revision carries a resolved container
 * image, the same claimability gate [WorkerClaimResolver] applies to a single profile, just listed
 * for every grant at once instead of claimed one at a time. A grant to a pure exec/env profile (no
 * image, or one that hasn't resolved) is silently omitted — there is nothing here for the agent to
 * run as a background container.
 *
 * Worker-authenticated the same way as [WorkerRunService] (`Bearer <workerId>.<secret>`), so it
 * rides the same `v2WorkerCredentialDrop` decorator and stays part of the unprobeable v2 plane.
 */
class WorkerAssignmentsService(
    private val fleetStore: FleetStore,
) : HttpService {
  override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse =
      HttpResponse.of({ runBlocking { handle(req) } }, ctx.blockingTaskExecutor())

  private suspend fun handle(req: HttpRequest): HttpResponse {
    val worker = authenticate(req) ?: return unauthorized()
    fleetStore.touchLastSeen(worker.workerId)

    val assignments =
        fleetStore.listGrantedProfileIds(worker.workerId).mapNotNull { profileId ->
          val profile = fleetStore.getProfile(profileId) ?: return@mapNotNull null
          if (profile.archived) return@mapNotNull null
          val revision = fleetStore.getLatestProfileRevision(profileId) ?: return@mapNotNull null
          if (revision.imageStatus != ImageStatus.RESOLVED) return@mapNotNull null
          AgentAssignment(
              profileId = profileId.value,
              revision = revision.revision,
              dockerImage = revision.dockerImage.orEmpty(),
              dockerImageDigest = revision.dockerImageDigest.orEmpty(),
          )
        }

    return jsonResponse(
        HttpStatus.OK,
        AgentAssignmentsResponse(assignments = assignments, paused = worker.paused),
    )
  }

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

  private fun jsonResponse(status: HttpStatus, body: AgentAssignmentsResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun jsonResponse(status: HttpStatus, body: WorkerErrorResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))
}
