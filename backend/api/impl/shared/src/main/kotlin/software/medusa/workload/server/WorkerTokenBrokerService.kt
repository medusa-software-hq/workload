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

/**
 * The worker-plane claim body. [audience]/[includeEmail] are used only by the ID-token endpoint
 * (`/worker/{v1,v2}/id-token`); `/token` and `/claim` ignore them, so the shared resolver parses
 * one shape for all three.
 */
@Serializable
internal data class TokenClaimRequest(
    val profileId: String,
    val audience: String? = null,
    val includeEmail: Boolean = false,
)

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
 * service account of a profile granted to it. Identity-only — for the token plus the revision's
 * full env/secret payload, see [WorkerClaimService] (`/worker/v1/claim`).
 *
 * Never logs the worker secret, the Authorization header, or the minted access token.
 */
class WorkerTokenBrokerService(
    private val fleetStore: FleetStore,
    private val tokenMinter: TokenMinter,
    private val tokenLifetimeSeconds: Long = 900L,
) : HttpService {
  private val resolver = WorkerClaimResolver(fleetStore, auditEventPrefix = "worker_gcp_token")

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
    val outcome = resolver.resolve(requestId, sourceIp, req)
    val claim =
        when (outcome) {
          is ClaimOutcome.Denied -> return outcome.response
          is ClaimOutcome.Allowed -> outcome.claim
        }
    val (workerId, profileId, revision) = claim

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

  private fun jsonResponse(status: HttpStatus, body: WorkerTokenResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun jsonResponse(status: HttpStatus, body: WorkerErrorResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun audit(
      requestId: String,
      sourceIp: String,
      workerId: WorkerId,
      profileId: ProfileId,
      revision: Int,
      targetServiceAccount: String,
      result: String,
      expiresAt: String? = null,
      reason: String? = null,
  ) {
    audit(
        AuditLogEntry(
            event = "worker_gcp_token_${if (result == "success") "issued" else "denied"}",
            requestId = requestId,
            timestamp = Instant.now().toString(),
            sourceIp = sourceIp,
            workerId = workerId.value.toString(),
            profileId = profileId.value,
            revision = revision,
            targetServiceAccount = targetServiceAccount,
            result = result,
            expiresAt = expiresAt,
            reason = reason,
        )
    )
  }
}
