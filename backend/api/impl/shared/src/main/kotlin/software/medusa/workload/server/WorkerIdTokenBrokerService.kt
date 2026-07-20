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
internal data class WorkerIdTokenResponse(
    val idToken: String,
    val expiresAt: String,
    val serviceAccount: String,
    val profileId: String,
    val revision: Int,
    val audience: String,
)

/**
 * Implements `POST /worker/{v1,v2}/id-token`: a worker claims an OIDC **identity** token — an
 * audience-bound JWT the profile's target service account is the subject of — for authenticating to
 * a non-Google service (e.g. Flow's API) that verifies it offline against Google's public keys.
 *
 * The counterpart to [WorkerTokenBrokerService] (`/token`, access tokens for Google's own APIs); it
 * shares the same [WorkerClaimResolver] (auth + grant + revision), then mints via
 * [TokenMinter.mintIdToken] instead of `mint`.
 *
 * **Audience is caller-chosen and free-form** — full GCE parity. Workers are trusted in-house
 * hardware, the grant ceremony is where that trust is established, and the same claim would issue
 * the strictly stronger access token anyway; restricting audiences after granting is ceremony, not
 * security. Every mint is audit-logged with its audience.
 *
 * Never logs the worker secret, the Authorization header, or the minted token.
 */
class WorkerIdTokenBrokerService(
    private val fleetStore: FleetStore,
    private val tokenMinter: TokenMinter,
) : HttpService {
  private val resolver = WorkerClaimResolver(fleetStore, auditEventPrefix = "worker_id_token")

  override fun serve(ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
    val requestId = UUID.randomUUID().toString()
    val sourceIp = ctx.clientAddress().hostAddress
    return HttpResponse.of(
        { runBlocking { handle(requestId, sourceIp, req) } },
        ctx.blockingTaskExecutor(),
    )
  }

  private suspend fun handle(requestId: String, sourceIp: String, req: HttpRequest): HttpResponse {
    val claim =
        when (val outcome = resolver.resolve(requestId, sourceIp, req)) {
          is ClaimOutcome.Denied -> return outcome.response
          is ClaimOutcome.Allowed -> outcome.claim
        }

    val audience = claim.audience
    if (audience.isNullOrBlank()) {
      audit(requestId, sourceIp, claim, result = "invalid_request", reason = "missing_audience")
      return jsonResponse(HttpStatus.BAD_REQUEST, WorkerErrorResponse("invalid_audience"))
    }

    return try {
      val minted =
          tokenMinter.mintIdToken(
              claim.revision.targetServiceAccount,
              audience = audience,
              includeEmail = claim.includeEmail,
          )
      audit(
          requestId,
          sourceIp,
          claim,
          result = "success",
          audience = audience,
          expiresAt = minted.expiresAt.toString(),
      )
      jsonResponse(
          HttpStatus.OK,
          WorkerIdTokenResponse(
              idToken = minted.idToken,
              expiresAt = minted.expiresAt.toString(),
              serviceAccount = claim.revision.targetServiceAccount,
              profileId = claim.profileId.value,
              revision = claim.revision.revision,
              audience = audience,
          ),
      )
    } catch (e: Exception) {
      audit(
          requestId,
          sourceIp,
          claim,
          result = "denied",
          audience = audience,
          reason = e::class.simpleName,
      )
      jsonResponse(HttpStatus.BAD_GATEWAY, WorkerErrorResponse("failed_to_mint_token"))
    }
  }

  private fun jsonResponse(status: HttpStatus, body: WorkerIdTokenResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun jsonResponse(status: HttpStatus, body: WorkerErrorResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun audit(
      requestId: String,
      sourceIp: String,
      claim: ResolvedClaim,
      result: String,
      audience: String? = null,
      expiresAt: String? = null,
      reason: String? = null,
  ) {
    audit(
        AuditLogEntry(
            event = "worker_id_token_${if (result == "success") "issued" else "denied"}",
            requestId = requestId,
            timestamp = Instant.now().toString(),
            sourceIp = sourceIp,
            workerId = claim.workerId.value.toString(),
            profileId = claim.profileId.value,
            revision = claim.revision.revision,
            targetServiceAccount = claim.revision.targetServiceAccount,
            result = result,
            audience = audience,
            expiresAt = expiresAt,
            reason = reason,
        )
    )
  }
}
