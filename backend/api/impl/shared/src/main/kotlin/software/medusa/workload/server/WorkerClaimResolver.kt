package software.medusa.workload.server

import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/** What a worker asked for, resolved to a concrete grantable revision. */
internal data class ResolvedClaim(
    val workerId: WorkerId,
    val profileId: ProfileId,
    val revision: ProfileRevision,
)

internal sealed interface ClaimOutcome {
  data class Allowed(val claim: ResolvedClaim) : ClaimOutcome

  data class Denied(val response: HttpResponse) : ClaimOutcome
}

/**
 * The auth/authorization pipeline shared by `/worker/v1/token` and `/worker/v1/claim`: parses the
 * `Bearer <workerId>.<workerSecret>` credentials, checks the worker is active, parses the
 * `{profileId}` body, and checks the profile exists/isn't archived/is granted/has a verified
 * revision. Every denial is audit-logged with [auditEventPrefix]; callers audit their own success
 * (which differs: minting can still fail after this resolves cleanly).
 *
 * Auth failures (unknown worker, wrong secret, worker not active) are all reported identically as
 * `401 unauthorized` — the caller can't distinguish them, only the audit log records the precise
 * reason. Authorization failures (profile missing/archived, no grant, unverified revision) are
 * reported as distinct `403` bodies, since that state isn't secret once the caller has proven who
 * they are.
 */
internal class WorkerClaimResolver(
    private val fleetStore: FleetStore,
    private val auditEventPrefix: String,
) {
  suspend fun resolve(requestId: String, sourceIp: String, req: HttpRequest): ClaimOutcome {
    val token = extractBearerToken(req)
    val credentials = token?.let { parseWorkerBearerToken(it) }
    if (credentials == null) {
      audit(requestId, sourceIp, result = "unauthorized", reason = "malformed_bearer_token")
      return ClaimOutcome.Denied(unauthorized())
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
      return ClaimOutcome.Denied(unauthorized())
    }
    if (!verifyWorkerSecret(secret, worker.secretHash)) {
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          result = "unauthorized",
          reason = "invalid_secret",
      )
      return ClaimOutcome.Denied(unauthorized())
    }
    if (worker.status != WorkerStatus.ACTIVE) {
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          result = "unauthorized",
          reason = "worker_not_active",
      )
      return ClaimOutcome.Denied(unauthorized())
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
          return ClaimOutcome.Denied(
              jsonResponse(HttpStatus.BAD_REQUEST, WorkerErrorResponse("invalid_request"))
          )
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
      return ClaimOutcome.Denied(
          jsonResponse(HttpStatus.FORBIDDEN, WorkerErrorResponse("profile_not_found"))
      )
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
      return ClaimOutcome.Denied(
          jsonResponse(HttpStatus.FORBIDDEN, WorkerErrorResponse("profile_archived"))
      )
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
      return ClaimOutcome.Denied(
          jsonResponse(HttpStatus.FORBIDDEN, WorkerErrorResponse("not_granted"))
      )
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
              return ClaimOutcome.Denied(
                  jsonResponse(
                      HttpStatus.INTERNAL_SERVER_ERROR,
                      WorkerErrorResponse("internal_error"),
                  )
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
      return ClaimOutcome.Denied(
          jsonResponse(HttpStatus.FORBIDDEN, WorkerErrorResponse("not_verified"))
      )
    }

    // An image profile whose digest didn't pin (unresolvable/undetermined) is not claimable — a
    // distinct reason from verification, so the worker/operator sees which gate failed. A pure
    // exec/env profile has imageStatus NOT_APPLICABLE and sails through.
    if (
        revision.imageStatus != ImageStatus.NOT_APPLICABLE &&
            revision.imageStatus != ImageStatus.RESOLVED
    ) {
      audit(
          requestId,
          sourceIp,
          workerId = workerId,
          profileId = profileId,
          revision = revision.revision,
          result = "forbidden",
          reason = revision.imageStatus.name.lowercase(),
      )
      return ClaimOutcome.Denied(
          jsonResponse(HttpStatus.FORBIDDEN, WorkerErrorResponse("image_unresolvable"))
      )
    }

    return ClaimOutcome.Allowed(ResolvedClaim(workerId, profileId, revision))
  }

  private fun unauthorized(): HttpResponse =
      jsonResponse(HttpStatus.UNAUTHORIZED, WorkerErrorResponse("unauthorized"))

  private fun jsonResponse(status: HttpStatus, body: WorkerErrorResponse): HttpResponse =
      HttpResponse.of(status, MediaType.JSON, workerJson.encodeToString(body))

  private fun audit(
      requestId: String,
      sourceIp: String,
      result: String,
      workerId: WorkerId? = null,
      profileId: ProfileId? = null,
      revision: Int? = null,
      reason: String? = null,
  ) {
    audit(
        AuditLogEntry(
            event = "${auditEventPrefix}_denied",
            requestId = requestId,
            timestamp = java.time.Instant.now().toString(),
            sourceIp = sourceIp,
            workerId = workerId?.value?.toString(),
            profileId = profileId?.value,
            revision = revision,
            result = result,
            reason = reason,
        )
    )
  }
}
