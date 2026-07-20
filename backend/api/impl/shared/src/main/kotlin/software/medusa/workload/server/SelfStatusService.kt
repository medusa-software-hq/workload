package software.medusa.workload.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString

private val pollableStatuses = setOf(WorkerStatus.PENDING, WorkerStatus.ACTIVE)

@Serializable
internal data class SelfStatusResponse(
    val status: String,
    val name: String,
    val grantedProfileIds: List<String> = emptyList(),
)

/**
 * Implements `GET /worker/v2/registrations/self`: a worker polling its own approval status,
 * authenticated with `Bearer <workerId>.<workerSecret>` (accepted while `pending` or `active`).
 *
 * Failed auth (wrong/missing token, unknown worker, wrong secret, or a worker in a status other
 * than pending/active — including one that just TTL-expired) is audit-logged the same way as a
 * failed token-broker call: "unauthorized", no further detail about which check failed. Only
 * successful polls get their own audit event.
 */
class SelfStatusService(
    private val fleetStore: FleetStore,
) : HttpService {
  override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
    val requestId = UUID.randomUUID().toString()
    val sourceIp = ctx.clientAddress().hostAddress

    val credentials = extractBearerToken(req)?.let { parseWorkerBearerToken(it) }
    if (credentials == null) {
      return unauthorized(requestId, sourceIp)
    }
    val (workerId, secret) = credentials

    // The store lookup may block; keep off Armeria's event-loop thread.
    return HttpResponse.of(
        {
          val worker = runBlocking { fleetStore.getWorker(workerId) }
          if (
              worker == null ||
                  !verifyWorkerSecret(secret, worker.secretHash) ||
                  worker.status !in pollableStatuses
          ) {
            unauthorized(requestId, sourceIp)
          } else {
            audit(
                AuditLogEntry(
                    event = "worker_status_polled",
                    requestId = requestId,
                    timestamp = Instant.now().toString(),
                    sourceIp = sourceIp,
                    workerId = worker.workerId.value.toString(),
                    result = "success",
                )
            )
            val grantedProfileIds =
                if (worker.status == WorkerStatus.ACTIVE) {
                  runBlocking { fleetStore.listGrantedProfileIds(worker.workerId) }.map { it.value }
                } else {
                  emptyList()
                }
            jsonResponse(
                HttpStatus.OK,
                SelfStatusResponse(
                    status = worker.status.name.lowercase(),
                    name = worker.name,
                    grantedProfileIds = grantedProfileIds,
                ),
            )
          }
        },
        ctx.blockingTaskExecutor(),
    )
  }

  private fun unauthorized(requestId: String, sourceIp: String): HttpResponse {
    audit(
        AuditLogEntry(
            event = "worker_status_poll_denied",
            requestId = requestId,
            timestamp = Instant.now().toString(),
            sourceIp = sourceIp,
            result = "unauthorized",
        )
    )
    return jsonResponse(HttpStatus.UNAUTHORIZED, WorkerErrorResponse("unauthorized"))
  }

  private fun jsonResponse(status: HttpStatus, body: SelfStatusResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun jsonResponse(status: HttpStatus, body: WorkerErrorResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))
}
