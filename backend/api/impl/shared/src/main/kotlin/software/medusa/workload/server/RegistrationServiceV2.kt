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
import java.util.function.Supplier
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import software.medusa.workload.tokenformat.TokenKind
import software.medusa.workload.tokenformat.WorkloadToken

private const val v2RegistrationsPerIpPerMinute = 20

@Serializable
internal data class RegisterWorkerV2Request(
    val name: String,
    val hostname: String? = null,
    val os: String? = null,
    val cliVersion: String? = null,
)

@Serializable
internal data class RegisterWorkerV2Response(
    val workerId: String,
    val workerSecret: String,
)

/**
 * Implements `POST /worker/v2/registrations` — the enrollment-token exchange (M4-A3). The Bearer
 * credential is a one-time `wle_` enrollment token; a valid one is atomically burnt and swapped for
 * a fresh `wlw_` worker secret, returning `{workerId, workerSecret}` with no confirmation code. The
 * worker lands `active`, or `pending` when the token was minted requiring approval.
 *
 * Absent or malformed credentials are dropped upstream by the `v2EnrollmentTokenDrop` decorator
 * (bare 404, counted, not audited), so this handler only ever sees a well-formed `wle_` token.
 * Everything else that isn't a successful exchange — unknown/expired/already-used token, a
 * rate-limited caller, a malformed body — also returns a **bare 404** with no body, audit-logged
 * with the true reason, so the whole plane is unprobeable. A modest per-IP rate limit shields the
 * database from load. Unlike v1 this endpoint is deliberately not behind the UUID path prefix — the
 * `wle_`/`wlw_` format is the filter now.
 *
 * Never logs the worker secret or the response body containing it.
 */
class RegistrationServiceV2(
    private val fleetStore: FleetStore,
    private val rateLimiter: PerSourceIpRateLimiter =
        PerSourceIpRateLimiter(
            maxRequestsPerWindow = v2RegistrationsPerIpPerMinute,
            window = Duration.ofMinutes(1),
        ),
    private val clock: () -> Instant = Instant::now,
) : HttpService {

  override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
    val requestId = UUID.randomUUID().toString()
    val sourceIp = ctx.clientAddress().hostAddress

    // Absent/malformed credentials are already dropped upstream by v2EnrollmentTokenDrop (bare 404,
    // counted, not audited), so a well-formed wle_ token is guaranteed here. The per-IP rate limit
    // still shields the DB from a flood of format-valid but unredeemable tokens.
    if (!rateLimiter.tryAcquire(sourceIp)) return drop(requestId, sourceIp, "rate_limited")
    val token = extractBearerToken(req) ?: return drop(requestId, sourceIp, "missing_token")

    // Reading the body and redeeming both may block; keep off Armeria's event-loop thread.
    return HttpResponse.of(
        Supplier {
          val request =
              try {
                workerJson.decodeFromString<RegisterWorkerV2Request>(
                    req.aggregate().join().contentUtf8()
                )
              } catch (e: Exception) {
                return@Supplier drop(requestId, sourceIp, "invalid_request", e::class.simpleName)
              }

          val secret = WorkloadToken.generate(TokenKind.WORKER)
          val worker =
              runBlocking {
                fleetStore.registerWorkerWithEnrollmentToken(
                    tokenHash = hashEnrollmentToken(token),
                    now = clock(),
                    secretHash = hashWorkerSecret(secret),
                    name = request.name,
                    hostname = request.hostname,
                    os = request.os,
                    cliVersion = request.cliVersion,
                    sourceIp = sourceIp,
                )
              } ?: return@Supplier drop(requestId, sourceIp, "token_not_redeemable")

          audit(
              AuditLogEntry(
                  event = "worker_registered_v2",
                  requestId = requestId,
                  timestamp = Instant.now().toString(),
                  sourceIp = sourceIp,
                  workerId = worker.workerId.value.toString(),
                  result = "success",
                  reason = "status:${worker.status.name.lowercase()}",
              )
          )
          HttpResponse.of(
              HttpStatus.OK,
              MediaType.JSON,
              workerJson.encodeToString(
                  RegisterWorkerV2Response(
                      workerId = worker.workerId.value.toString(),
                      workerSecret = secret,
                  )
              ),
          )
        },
        ctx.blockingTaskExecutor(),
    )
  }

  /** Audit-log the true rejection [reason] server-side, then return the uniform bare 404. */
  private fun drop(
      requestId: String,
      sourceIp: String,
      reason: String,
      detail: String? = null,
  ): HttpResponse {
    audit(
        AuditLogEntry(
            event = "worker_registration_v2_rejected",
            requestId = requestId,
            timestamp = Instant.now().toString(),
            sourceIp = sourceIp,
            result = reason,
            reason = detail,
        )
    )
    return bareNotFound()
  }
}
