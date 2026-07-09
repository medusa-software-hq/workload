package software.medusa.workload.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

private const val registrationsPerIpPerMinute = 20

@Serializable
internal data class RegisterWorkerRequest(
    val name: String,
    val hostname: String? = null,
    val os: String? = null,
    val cliVersion: String? = null,
)

@Serializable
internal data class RegisterWorkerResponse(
    val workerId: String,
    val workerSecret: String,
    val confirmationCode: String,
)

/**
 * Implements `POST /worker/v1/registrations`: creates a new `pending` worker. Unauthenticated
 * (anyone with the URL can register), rate-limited per source IP — that's the anti-abuse story, not
 * a security boundary; see the M1 registration design.
 *
 * Never logs the worker secret or the response body containing it.
 */
class RegistrationService(
    private val fleetStore: FleetStore,
    private val rateLimiter: PerSourceIpRateLimiter =
        PerSourceIpRateLimiter(
            maxRequestsPerWindow = registrationsPerIpPerMinute,
            window = Duration.ofMinutes(1),
        ),
) : HttpService {

  override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
    val requestId = UUID.randomUUID().toString()
    val sourceIp = ctx.clientAddress().hostAddress

    if (!rateLimiter.tryAcquire(sourceIp)) {
      audit(
          AuditLogEntry(
              event = "worker_registration_rate_limited",
              requestId = requestId,
              timestamp = Instant.now().toString(),
              sourceIp = sourceIp,
              result = "rate_limited",
          )
      )
      return jsonResponse(HttpStatus.TOO_MANY_REQUESTS, WorkerErrorResponse("rate_limited"))
    }

    // Reading the body and calling the store both may block; keep off Armeria's event-loop thread.
    return HttpResponse.of(
        {
          val body = req.aggregate().join().contentUtf8()
          try {
            val request = workerJson.decodeFromString<RegisterWorkerRequest>(body)
            val secret = generateWorkerSecret()
            val worker = runBlocking {
              fleetStore.createWorker(
                  NewWorker(
                      secretHash = hashWorkerSecret(secret),
                      name = request.name,
                      hostname = request.hostname,
                      os = request.os,
                      cliVersion = request.cliVersion,
                      confirmationCode = generateConfirmationCode(),
                  )
              )
            }
            audit(
                AuditLogEntry(
                    event = "worker_registered",
                    requestId = requestId,
                    timestamp = Instant.now().toString(),
                    sourceIp = sourceIp,
                    workerId = worker.workerId.value.toString(),
                    result = "success",
                )
            )
            jsonResponse(
                HttpStatus.OK,
                RegisterWorkerResponse(
                    workerId = worker.workerId.value.toString(),
                    workerSecret = secret,
                    confirmationCode = worker.confirmationCode.orEmpty(),
                ),
            )
          } catch (e: Exception) {
            audit(
                AuditLogEntry(
                    event = "worker_registration_failed",
                    requestId = requestId,
                    timestamp = Instant.now().toString(),
                    sourceIp = sourceIp,
                    result = "invalid_request",
                    reason = e::class.simpleName,
                )
            )
            jsonResponse(HttpStatus.BAD_REQUEST, WorkerErrorResponse("invalid_request"))
          }
        },
        ctx.blockingTaskExecutor(),
    )
  }

  private fun jsonResponse(status: HttpStatus, body: RegisterWorkerResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun jsonResponse(status: HttpStatus, body: WorkerErrorResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))
}
