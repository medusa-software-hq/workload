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

@Serializable
internal data class CreateRunRequest(
    val profileId: String? = null,
    val revision: Int? = null,
    // "run" | "exec" (lowercased kind name); validated against RunKind.
    val kind: String,
    val imageDigest: String? = null,
)

@Serializable
internal data class CreateRunResponse(
    val runId: String,
    // Server-controlled cadence the CLI heartbeats on — one knob, changed server-side.
    val heartbeatIntervalSeconds: Long,
)

@Serializable internal data class EndRunRequest(val exitCode: Int? = null)

/**
 * Implements the worker-plane run lifecycle (M6-B1), the source of "who is running what":
 * - `POST /worker/v2/runs` `{profileId, revision, kind, imageDigest?}` → `{runId,
 *   heartbeatIntervalSeconds}` — records a run at start (after the worker's claim).
 * - `POST /worker/v2/runs/{runId}/heartbeat` → 204 — keeps a run live; also touches the worker's
 *   `last_seen_at`.
 * - `POST /worker/v2/runs/{runId}/end` `{exitCode?}` → 204 — records the terminal state.
 *
 * Worker-authenticated by the same `Bearer <workerId>.<secret>` credential as the rest of the v2
 * plane; auth failures come back as `401` (rewritten to a bare `404` by the credential-drop
 * decorator, keeping the plane unprobeable). Post-auth, a run belongs to the authenticated worker: a
 * heartbeat/end for a run owned by someone else is `403`, an unknown run `404` — real errors, since
 * the caller has proven who they are. `lost` is never written here (it's a read-time derivation);
 * this service only ever writes RUNNING/SUCCEEDED/FAILED.
 */
class WorkerRunService(
    private val fleetStore: FleetStore,
) : HttpService {
  override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
    val requestId = UUID.randomUUID().toString()
    val sourceIp = ctx.clientAddress().hostAddress
    // Store reads/writes and body aggregation may block; keep off the event loop.
    return HttpResponse.of(
        { runBlocking { handle(ctx, requestId, sourceIp, req) } },
        ctx.blockingTaskExecutor(),
    )
  }

  private suspend fun handle(
      ctx: ServiceRequestContext,
      requestId: String,
      sourceIp: String,
      req: HttpRequest,
  ): HttpResponse {
    val worker = authenticate(req) ?: return unauthorized()
    val runIdParam = ctx.pathParam("runId")
    return when {
      runIdParam == null -> createRun(requestId, sourceIp, worker, req)
      ctx.path().endsWith("/heartbeat") -> heartbeat(worker, runIdParam)
      else -> endRun(requestId, sourceIp, worker, runIdParam, req)
    }
  }

  private suspend fun authenticate(req: HttpRequest): Worker? {
    val (workerId, secret) = extractBearerToken(req)?.let { parseWorkerBearerToken(it) } ?: return null
    val worker = fleetStore.getWorker(workerId) ?: return null
    if (!verifyWorkerSecret(secret, worker.secretHash)) return null
    if (worker.status != WorkerStatus.ACTIVE) return null
    return worker
  }

  private suspend fun createRun(
      requestId: String,
      sourceIp: String,
      worker: Worker,
      req: HttpRequest,
  ): HttpResponse {
    val body = req.aggregate().join().contentUtf8()
    val request =
        try {
          workerJson.decodeFromString<CreateRunRequest>(body)
        } catch (e: Exception) {
          return badRequest("invalid_request")
        }
    val kind =
        runCatching { RunKind.valueOf(request.kind.uppercase()) }.getOrNull()
            ?: return badRequest("invalid_kind")

    val run =
        fleetStore.createRun(
            NewRun(
                workerId = worker.workerId,
                profileId = request.profileId?.let { ProfileId(it) },
                revision = request.revision,
                kind = kind,
                imageDigest = request.imageDigest,
            )
        )
    fleetStore.touchLastSeen(worker.workerId)
    audit(
        requestId,
        sourceIp,
        event = "worker_run_started",
        result = "success",
        worker = worker,
        run = run,
    )
    return jsonResponse(
        HttpStatus.OK,
        CreateRunResponse(
            runId = run.runId.value.toString(),
            heartbeatIntervalSeconds = heartbeatInterval.seconds,
        ),
    )
  }

  private suspend fun heartbeat(worker: Worker, runIdParam: String): HttpResponse {
    val runId = parseRunId(runIdParam) ?: return notFound()
    val run = fleetStore.getRun(runId) ?: return notFound()
    if (run.workerId != worker.workerId) return forbidden()
    // No-op if the run has already ended; a heartbeat never revives a terminal run.
    fleetStore.heartbeatRun(runId)
    fleetStore.touchLastSeen(worker.workerId)
    return noContent()
  }

  private suspend fun endRun(
      requestId: String,
      sourceIp: String,
      worker: Worker,
      runIdParam: String,
      req: HttpRequest,
  ): HttpResponse {
    val runId = parseRunId(runIdParam) ?: return notFound()
    val run = fleetStore.getRun(runId) ?: return notFound()
    if (run.workerId != worker.workerId) return forbidden()

    val body = req.aggregate().join().contentUtf8()
    val request =
        try {
          if (body.isBlank()) EndRunRequest() else workerJson.decodeFromString<EndRunRequest>(body)
        } catch (e: Exception) {
          return badRequest("invalid_request")
        }

    // Idempotent: a second `end` (or one racing the first) finds nothing to write and returns 204.
    val ended = fleetStore.endRun(runId, request.exitCode)
    if (ended != null) {
      audit(
          requestId,
          sourceIp,
          event = "worker_run_ended",
          result = "success",
          worker = worker,
          run = ended,
          exitCode = request.exitCode,
      )
    }
    return noContent()
  }

  private fun parseRunId(value: String): RunId? =
      runCatching { RunId(UUID.fromString(value)) }.getOrNull()

  private fun unauthorized(): HttpResponse =
      jsonResponse(HttpStatus.UNAUTHORIZED, WorkerErrorResponse("unauthorized"))

  private fun forbidden(): HttpResponse =
      jsonResponse(HttpStatus.FORBIDDEN, WorkerErrorResponse("not_your_run"))

  private fun notFound(): HttpResponse =
      jsonResponse(HttpStatus.NOT_FOUND, WorkerErrorResponse("run_not_found"))

  private fun badRequest(error: String): HttpResponse =
      jsonResponse(HttpStatus.BAD_REQUEST, WorkerErrorResponse(error))

  private fun noContent(): HttpResponse = HttpResponse.of(HttpStatus.NO_CONTENT)

  private fun jsonResponse(status: HttpStatus, body: CreateRunResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun jsonResponse(status: HttpStatus, body: WorkerErrorResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun audit(
      requestId: String,
      sourceIp: String,
      event: String,
      result: String,
      worker: Worker,
      run: Run,
      exitCode: Int? = null,
  ) {
    audit(
        AuditLogEntry(
            event = event,
            requestId = requestId,
            timestamp = Instant.now().toString(),
            sourceIp = sourceIp,
            result = result,
            workerId = worker.workerId.value.toString(),
            profileId = run.profileId?.value,
            revision = run.revision,
            runId = run.runId.value.toString(),
            kind = run.kind.name.lowercase(),
            exitCode = exitCode,
        )
    )
  }
}
