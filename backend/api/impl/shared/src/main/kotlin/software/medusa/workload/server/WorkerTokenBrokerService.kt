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
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

@Serializable internal data class TokenClaimRequest(val profileId: String)

@Serializable
internal data class WorkerTokenResponse(
    val accessToken: String,
    val expiresAt: String,
    val serviceAccount: String,
    val profileId: String,
    val revision: Int,
)

/**
 * Implements `POST /worker/v1/token`: a worker (authenticated as `Bearer
 * <workerId>.<workerSecret>`) claims a short-lived GCP access token impersonating the target
 * service account of a profile granted to it.
 *
 * Auth failures (unknown worker, wrong secret, worker not active) are all reported identically as
 * `401 unauthorized` — the caller can't distinguish them, only the audit log records the precise
 * reason. Authorization failures (profile missing/archived, no grant) are reported as distinct
 * `403` bodies, since grant state isn't secret once the caller has proven who they are.
 *
 * Never logs the worker secret, the Authorization header, or the minted access token.
 */
class WorkerTokenBrokerService(
    private val fleetStore: FleetStore,
    private val tokenMinter: TokenMinter,
    private val tokenLifetimeSeconds: Long = 900L,
) : HttpService {

  override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
    val requestId = UUID.randomUUID().toString()
    val sourceIp = ctx.clientAddress().hostAddress

    // Reading the body, checking the store, and minting all may block; keep off the event loop.
    return HttpResponse.of(
        { runBlocking { handle(requestId, sourceIp, req) } },
        ctx.blockingTaskExecutor(),
    )
  }

  private suspend fun handle(requestId: String, sourceIp: String, req: HttpRequest): HttpResponse {
    val token = extractBearerToken(req)
    val credentials = token?.let { parseWorkerBearerToken(it) }
    if (credentials == null) {
      audit(requestId, sourceIp, result = "unauthorized", reason = "malformed_bearer_token")
      return unauthorized()
    }
    val (workerId, secret) = credentials

    val worker = fleetStore.getWorker(workerId)
    if (worker == null) {
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          result = "unauthorized",
          reason = "unknown_worker",
      )
      return unauthorized()
    }
    if (!verifyWorkerSecret(secret, worker.secretHash)) {
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          result = "unauthorized",
          reason = "invalid_secret",
      )
      return unauthorized()
    }
    if (worker.status != WorkerStatus.ACTIVE) {
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          result = "unauthorized",
          reason = "worker_not_active",
      )
      return unauthorized()
    }

    val body = req.aggregate().join().contentUtf8()
    val request =
        try {
          workerJson.decodeFromString<TokenClaimRequest>(body).also {
            require(it.profileId.isNotBlank())
          }
        } catch (e: Exception) {
          audit(
              requestId,
              sourceIp,
              workerId = workerId,
              result = "invalid_request",
              reason = "malformed_body",
          )
          return jsonResponse(HttpStatus.BAD_REQUEST, WorkerErrorResponse("invalid_request"))
        }
    val profileId = ProfileId(request.profileId)

    fleetStore.touchLastSeen(workerId)

    val profile = fleetStore.getProfile(profileId)
    if (profile == null) {
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          profileId = profileId,
          result = "forbidden",
          reason = "profile_not_found",
      )
      return jsonResponse(HttpStatus.FORBIDDEN, WorkerErrorResponse("profile_not_found"))
    }
    if (profile.archived) {
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          profileId = profileId,
          result = "forbidden",
          reason = "profile_archived",
      )
      return jsonResponse(HttpStatus.FORBIDDEN, WorkerErrorResponse("profile_archived"))
    }
    if (!fleetStore.hasGrant(workerId, profileId)) {
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          profileId = profileId,
          result = "forbidden",
          reason = "not_granted",
      )
      return jsonResponse(HttpStatus.FORBIDDEN, WorkerErrorResponse("not_granted"))
    }

    val revision =
        fleetStore.getLatestProfileRevision(profileId)
            ?: run {
              audit(
                  requestId,
                  sourceIp,
                  workerId = workerId,
                  profileId = profileId,
                  result = "denied",
                  reason = "missing_revision",
              )
              return jsonResponse(
                  HttpStatus.INTERNAL_SERVER_ERROR,
                  WorkerErrorResponse("internal_error"),
              )
            }

    if (revision.verificationStatus != VerificationStatus.VERIFIED) {
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          profileId = profileId,
          revision = revision.revision,
          result = "forbidden",
          reason = revision.verificationStatus.name.lowercase(),
      )
      return jsonResponse(HttpStatus.FORBIDDEN, WorkerErrorResponse("not_verified"))
    }

    return try {
      val minted = tokenMinter.mint(revision.targetServiceAccount, tokenLifetimeSeconds)
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          profileId = profileId,
          revision = revision.revision,
          targetServiceAccount = revision.targetServiceAccount,
          result = "success",
          expiresAt = minted.expiresAt.toString(),
      )
      jsonResponse(
          HttpStatus.OK,
          WorkerTokenResponse(
              accessToken = minted.accessToken,
              expiresAt = minted.expiresAt.toString(),
              serviceAccount = revision.targetServiceAccount,
              profileId = profileId.value,
              revision = revision.revision,
          ),
      )
    } catch (e: Exception) {
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          profileId = profileId,
          revision = revision.revision,
          targetServiceAccount = revision.targetServiceAccount,
          result = "denied",
          reason = e::class.simpleName,
      )
      jsonResponse(HttpStatus.BAD_GATEWAY, WorkerErrorResponse("failed_to_mint_token"))
    }
  }

  private fun unauthorized(): HttpResponse =
      jsonResponse(HttpStatus.UNAUTHORIZED, WorkerErrorResponse("unauthorized"))

  private fun jsonResponse(status: HttpStatus, body: WorkerTokenResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun jsonResponse(status: HttpStatus, body: WorkerErrorResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun audit(
      requestId: String,
      sourceIp: String,
      result: String,
      workerId: WorkerId? = null,
      profileId: ProfileId? = null,
      revision: Int? = null,
      targetServiceAccount: String? = null,
      expiresAt: String? = null,
      reason: String? = null,
  ) {
    audit(
        AuditLogEntry(
            event = "worker_gcp_token_${if (result == "success") "issued" else "denied"}",
            requestId = requestId,
            timestamp = Instant.now().toString(),
            sourceIp = sourceIp,
            workerId = workerId?.value?.toString(),
            profileId = profileId?.value,
            revision = revision,
            targetServiceAccount = targetServiceAccount,
            result = result,
            expiresAt = expiresAt,
            reason = reason,
        )
    )
  }
}
