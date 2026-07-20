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

@Serializable internal data class ClaimImage(val ref: String, val digest: String)

@Serializable
internal data class WorkerClaimResponse(
    val accessToken: String,
    val expiresAt: String,
    val serviceAccount: String,
    val profileId: String,
    val revision: Int,
    val envVars: Map<String, String> = emptyMap(),
    val secretEnvVars: Map<String, String> = emptyMap(),
    // Present for image profiles (ref + resolved digest), null for pure exec/env profiles.
    val image: ClaimImage? = null,
)

/**
 * Implements `POST /worker/v2/claim`: like `/worker/v2/token`, but the response also carries the
 * granted revision's env var payload — the foundation `workload exec` (M2 path A) and later
 * `workload run` build on. Secret **references only** ever cross the broker; values are resolved
 * worker-side, directly against Secret Manager, using the minted impersonated token.
 *
 * Never logs the worker secret, the Authorization header, the minted access token, env values, or
 * secret_env_vars values (only their keys ride in the audit log via [AuditLogEntry], and even that
 * only implicitly through the revision reference — never the secret payload itself, which this
 * broker never touches).
 */
class WorkerClaimService(
    private val fleetStore: FleetStore,
    private val tokenMinter: TokenMinter,
    private val tokenLifetimeSeconds: Long = 900L,
) : HttpService {
  private val resolver = WorkerClaimResolver(fleetStore, auditEventPrefix = "worker_gcp_claim")

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
          WorkerClaimResponse(
              accessToken = minted.accessToken,
              expiresAt = minted.expiresAt.toString(),
              serviceAccount = revision.targetServiceAccount,
              profileId = profileId.value,
              revision = revision.revision,
              envVars = revision.envVars,
              secretEnvVars = revision.secretEnvVars,
              image =
                  revision.dockerImage?.let { ref ->
                    revision.dockerImageDigest?.let { digest -> ClaimImage(ref, digest) }
                  },
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

  private fun jsonResponse(status: HttpStatus, body: WorkerClaimResponse): HttpResponse =
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
            event = "worker_gcp_claim_${if (result == "success") "issued" else "denied"}",
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
