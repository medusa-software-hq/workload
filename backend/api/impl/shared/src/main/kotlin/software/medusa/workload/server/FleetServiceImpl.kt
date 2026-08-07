package software.medusa.workload.server

import io.grpc.Status
import io.grpc.StatusException
import java.time.Duration
import java.time.Instant
import java.util.UUID
import software.medusa.workload.tokenformat.TokenKind
import software.medusa.workload.tokenformat.WorkloadToken
import software.medusa.workload.v1.AgentAssignmentState as AgentAssignmentStateProto
import software.medusa.workload.v1.AgentAssignmentStatus as AgentAssignmentStatusProto
import software.medusa.workload.v1.ApproveWorkerRequest
import software.medusa.workload.v1.ApproveWorkerResponse
import software.medusa.workload.v1.ArchiveProfileRequest
import software.medusa.workload.v1.ArchiveProfileResponse
import software.medusa.workload.v1.Assignment as AssignmentProto
import software.medusa.workload.v1.CreateAssignmentRequest
import software.medusa.workload.v1.CreateAssignmentResponse
import software.medusa.workload.v1.CreateEnrollmentTokenRequest
import software.medusa.workload.v1.CreateEnrollmentTokenResponse
import software.medusa.workload.v1.CreateProfileRequest
import software.medusa.workload.v1.CreateProfileResponse
import software.medusa.workload.v1.DeleteAssignmentRequest
import software.medusa.workload.v1.DeleteAssignmentResponse
import software.medusa.workload.v1.EnrollmentToken as EnrollmentTokenProto
import software.medusa.workload.v1.FleetServiceGrpcKt
import software.medusa.workload.v1.GrantProfileRequest
import software.medusa.workload.v1.GrantProfileResponse
import software.medusa.workload.v1.ImageStatus as ImageStatusProto
import software.medusa.workload.v1.ListAssignmentsRequest
import software.medusa.workload.v1.ListAssignmentsResponse
import software.medusa.workload.v1.ListEnrollmentTokensRequest
import software.medusa.workload.v1.ListEnrollmentTokensResponse
import software.medusa.workload.v1.ListProfileRevisionsRequest
import software.medusa.workload.v1.ListProfileRevisionsResponse
import software.medusa.workload.v1.ListProfilesRequest
import software.medusa.workload.v1.ListProfilesResponse
import software.medusa.workload.v1.ListRunsRequest
import software.medusa.workload.v1.ListRunsResponse
import software.medusa.workload.v1.ListWorkersRequest
import software.medusa.workload.v1.ListWorkersResponse
import software.medusa.workload.v1.Profile as ProfileProto
import software.medusa.workload.v1.ProfileRevision as ProfileRevisionProto
import software.medusa.workload.v1.RejectWorkerRequest
import software.medusa.workload.v1.RejectWorkerResponse
import software.medusa.workload.v1.ResolveImageRequest
import software.medusa.workload.v1.ResolveImageResponse
import software.medusa.workload.v1.RevokeEnrollmentTokenRequest
import software.medusa.workload.v1.RevokeEnrollmentTokenResponse
import software.medusa.workload.v1.RevokeProfileGrantRequest
import software.medusa.workload.v1.RevokeProfileGrantResponse
import software.medusa.workload.v1.RevokeWorkerRequest
import software.medusa.workload.v1.RevokeWorkerResponse
import software.medusa.workload.v1.Run as RunProto
import software.medusa.workload.v1.RunKind as RunKindProto
import software.medusa.workload.v1.RunState as RunStateProto
import software.medusa.workload.v1.SetProfileFallbackEligibleRequest
import software.medusa.workload.v1.SetProfileFallbackEligibleResponse
import software.medusa.workload.v1.SetWorkerFallbackNodeRequest
import software.medusa.workload.v1.SetWorkerFallbackNodeResponse
import software.medusa.workload.v1.SetWorkerPausedRequest
import software.medusa.workload.v1.SetWorkerPausedResponse
import software.medusa.workload.v1.UpdateProfileRequest
import software.medusa.workload.v1.UpdateProfileResponse
import software.medusa.workload.v1.VerificationStatus as VerificationStatusProto
import software.medusa.workload.v1.VerifyProfileRequest
import software.medusa.workload.v1.VerifyProfileResponse
import software.medusa.workload.v1.Worker as WorkerProto
import software.medusa.workload.v1.WorkerStatus as WorkerStatusProto

private fun WorkerStatus.toProto(): WorkerStatusProto =
    when (this) {
      WorkerStatus.PENDING -> WorkerStatusProto.WORKER_STATUS_PENDING
      WorkerStatus.ACTIVE -> WorkerStatusProto.WORKER_STATUS_ACTIVE
      WorkerStatus.REJECTED -> WorkerStatusProto.WORKER_STATUS_REJECTED
      WorkerStatus.REVOKED -> WorkerStatusProto.WORKER_STATUS_REVOKED
    }

private fun VerificationStatus.toProto(): VerificationStatusProto =
    when (this) {
      VerificationStatus.UNVERIFIED -> VerificationStatusProto.VERIFICATION_STATUS_UNVERIFIED
      VerificationStatus.VERIFIED -> VerificationStatusProto.VERIFICATION_STATUS_VERIFIED
      VerificationStatus.BINDING_MISSING ->
          VerificationStatusProto.VERIFICATION_STATUS_BINDING_MISSING
      VerificationStatus.SECRET_INACCESSIBLE ->
          VerificationStatusProto.VERIFICATION_STATUS_SECRET_INACCESSIBLE
    }

private fun ImageStatus.toProto(): ImageStatusProto =
    when (this) {
      ImageStatus.NOT_APPLICABLE -> ImageStatusProto.IMAGE_STATUS_NOT_APPLICABLE
      ImageStatus.RESOLVED -> ImageStatusProto.IMAGE_STATUS_RESOLVED
      ImageStatus.UNRESOLVABLE -> ImageStatusProto.IMAGE_STATUS_UNRESOLVABLE
      ImageStatus.UNDETERMINED -> ImageStatusProto.IMAGE_STATUS_UNDETERMINED
    }

private fun Worker.toProto(grantedProfileIds: List<ProfileId>): WorkerProto =
    WorkerProto.newBuilder()
        .setWorkerId(workerId.value.toString())
        .setName(name)
        .setHostname(hostname.orEmpty())
        .setOs(os.orEmpty())
        .setCliVersion(cliVersion.orEmpty())
        .setStatus(status.toProto())
        .setCreatedAt(createdAt.toString())
        .setApprovedAt(approvedAt?.toString().orEmpty())
        .setApprovedBy(approvedBy.orEmpty())
        .setLastSeenAt(lastSeenAt?.toString().orEmpty())
        .addAllGrantedProfileIds(grantedProfileIds.map { it.value })
        .setRegisteredVia(registeredVia.name.lowercase())
        .setSourceIp(sourceIp.orEmpty())
        .setRevokedAt(revokedAt?.toString().orEmpty())
        .setPaused(paused)
        .setPausedAt(pausedAt?.toString().orEmpty())
        .setFallbackNode(fallbackNode)
        .addAllAssignmentStatuses(assignmentStatuses.map { it.toProto() })
        .build()

private fun AgentAssignmentState.toProto(): AgentAssignmentStateProto =
    when (this) {
      AgentAssignmentState.CONVERGED -> AgentAssignmentStateProto.AGENT_ASSIGNMENT_STATE_CONVERGED
      AgentAssignmentState.DRAINING -> AgentAssignmentStateProto.AGENT_ASSIGNMENT_STATE_DRAINING
      AgentAssignmentState.CRASHLOOP_HOLD ->
          AgentAssignmentStateProto.AGENT_ASSIGNMENT_STATE_CRASHLOOP_HOLD
      AgentAssignmentState.REPLACING -> AgentAssignmentStateProto.AGENT_ASSIGNMENT_STATE_REPLACING
    }

private fun AgentAssignmentStatus.toProto(): AgentAssignmentStatusProto =
    AgentAssignmentStatusProto.newBuilder()
        .setProfileId(profileId.value)
        .setRunningDigest(runningDigest.orEmpty())
        .setDesiredDigest(desiredDigest.orEmpty())
        .setState(state.toProto())
        .setSince(since.toString())
        .setDrainDeadline(drainDeadline?.toString().orEmpty())
        .build()

private fun RunKind.toProto(): RunKindProto =
    when (this) {
      RunKind.RUN -> RunKindProto.RUN_KIND_RUN
      RunKind.EXEC -> RunKindProto.RUN_KIND_EXEC
      RunKind.AGENT -> RunKindProto.RUN_KIND_AGENT
    }

private fun RunState.toProto(): RunStateProto =
    when (this) {
      RunState.RUNNING -> RunStateProto.RUN_STATE_RUNNING
      RunState.SUCCEEDED -> RunStateProto.RUN_STATE_SUCCEEDED
      RunState.FAILED -> RunStateProto.RUN_STATE_FAILED
      RunState.LOST -> RunStateProto.RUN_STATE_LOST
    }

private fun Run.toProto(workerName: String): RunProto =
    RunProto.newBuilder()
        .setRunId(runId.value.toString())
        .setWorkerId(workerId.value.toString())
        .setWorkerName(workerName)
        .setProfileId(profileId?.value.orEmpty())
        .setRevision(revision ?: 0)
        .setKind(kind.toProto())
        .setState(state.toProto())
        .setExitCode(exitCode ?: 0)
        .setHasExitCode(exitCode != null)
        .setStartedAt(startedAt.toString())
        .setLastHeartbeatAt(lastHeartbeatAt.toString())
        .setEndedAt(endedAt?.toString().orEmpty())
        .setImageDigest(imageDigest.orEmpty())
        .build()

private fun Profile.toProto(): ProfileProto =
    ProfileProto.newBuilder()
        .setProfileId(profileId.value)
        .setDisplayName(displayName.orEmpty())
        .setLatestRevision(latestRevision)
        .setArchived(archived)
        .setCreatedAt(createdAt.toString())
        .setFallbackEligible(fallbackEligible)
        .build()

private fun ProfileRevision.toProto(): ProfileRevisionProto =
    ProfileRevisionProto.newBuilder()
        .setProfileId(profileId.value)
        .setRevision(revision)
        .setTargetServiceAccount(targetServiceAccount)
        .setCreatedAt(createdAt.toString())
        .setCreatedBy(createdBy)
        .setNote(note.orEmpty())
        .setVerificationStatus(verificationStatus.toProto())
        .putAllEnvVars(envVars)
        .putAllSecretEnvVars(secretEnvVars)
        .setDockerImage(dockerImage.orEmpty())
        .setDockerImageDigest(dockerImageDigest.orEmpty())
        .setImageStatus(imageStatus.toProto())
        .setDrainDeadline(drainDeadline.orEmpty())
        .build()

private fun Assignment.toProto(): AssignmentProto =
    AssignmentProto.newBuilder()
        .setAssignmentId(id.value.toString())
        .setWorkerId(workerId.value.toString())
        .setProfileId(profileId.value)
        .setCreatedAt(createdAt.toString())
        .setCreatedBy(createdBy)
        .build()

private fun EnrollmentToken.toProto(): EnrollmentTokenProto =
    EnrollmentTokenProto.newBuilder()
        .setEnrollmentTokenId(id.value.toString())
        .setNote(note.orEmpty())
        .setCreatedBy(createdBy)
        .setCreatedAt(createdAt.toString())
        .setExpiresAt(expiresAt.toString())
        .setRequireApproval(requireApproval)
        .build()

/**
 * Default enrollment-token lifetime when the request leaves `expires_in_days` unset or
 * non-positive.
 */
private const val defaultEnrollmentTokenTtlDays = 7L

private fun parseEnrollmentTokenId(raw: String): EnrollmentTokenId =
    try {
      EnrollmentTokenId(UUID.fromString(raw))
    } catch (e: IllegalArgumentException) {
      throw StatusException(
          Status.INVALID_ARGUMENT.withDescription("Invalid enrollment_token_id: '$raw'")
      )
    }

private fun parseAssignmentId(raw: String): AssignmentId =
    try {
      AssignmentId(UUID.fromString(raw))
    } catch (e: IllegalArgumentException) {
      throw StatusException(
          Status.INVALID_ARGUMENT.withDescription("Invalid assignment_id: '$raw'")
      )
    }

private fun parseWorkerId(raw: String): WorkerId =
    try {
      WorkerId(UUID.fromString(raw))
    } catch (e: IllegalArgumentException) {
      throw StatusException(Status.INVALID_ARGUMENT.withDescription("Invalid worker_id: '$raw'"))
    }

private fun parseProfileId(raw: String): ProfileId =
    try {
      ProfileId(raw)
    } catch (e: IllegalArgumentException) {
      throw StatusException(Status.INVALID_ARGUMENT.withDescription(e.message))
    }

private fun requireTargetServiceAccount(value: String) {
  if (value.isBlank()) {
    throw StatusException(
        Status.INVALID_ARGUMENT.withDescription("target_service_account is required")
    )
  }
}

private val envVarNamePattern = Regex("[A-Z_][A-Z0-9_]*")
private val secretResourceNamePattern =
    Regex("""projects/[^/]+/secrets/[^/]+/versions/(latest|\d+)""")

/**
 * Validates env var names in both maps (`[A-Z_][A-Z0-9_]*`, no collisions between the two maps) and
 * the resource-name format of every `secretEnvVars` value.
 */
private fun requireValidEnvVars(envVars: Map<String, String>, secretEnvVars: Map<String, String>) {
  for (name in envVars.keys + secretEnvVars.keys) {
    if (!envVarNamePattern.matches(name)) {
      throw StatusException(
          Status.INVALID_ARGUMENT.withDescription(
              "env var name '$name' must match $envVarNamePattern"
          )
      )
    }
  }

  val collisions = envVars.keys.intersect(secretEnvVars.keys)
  if (collisions.isNotEmpty()) {
    throw StatusException(
        Status.INVALID_ARGUMENT.withDescription(
            "env var name(s) ${collisions.sorted().joinToString()} set in both env_vars and secret_env_vars"
        )
    )
  }

  for ((name, resourceName) in secretEnvVars) {
    if (!secretResourceNamePattern.matches(resourceName)) {
      throw StatusException(
          Status.INVALID_ARGUMENT.withDescription(
              "secret_env_vars['$name'] = '$resourceName' must match $secretResourceNamePattern"
          )
      )
    }
  }
}

// A container image ref that must live in a registry (a host with a dot/port before the first
// slash) — bare Docker Hub shorthand like `busybox:latest` is rejected; workload images are in
// Artifact Registry. Mirrors the console-side `validateImageRef` in fleetValidation.ts.
private val imageRefPattern = Regex("""[^\s/]+[.:][^\s/]*/\S+""")

/**
 * Validates a non-blank image ref; a blank ref means "no image" and is allowed.
 *
 * The registry must be Google's. That's a **security** rule, not a taste one: both the backend
 * (resolving the digest) and the worker (pulling) authenticate to the image's registry with an
 * access token impersonating the revision's target SA, so allowing an arbitrary host would let
 * anyone who can edit a profile harvest a live token for that service account. See
 * [isGoogleRegistryHost].
 */
private fun requireValidImageRef(dockerImage: String) {
  if (dockerImage.isBlank()) return
  val trimmed = dockerImage.trim()
  if (!imageRefPattern.matches(trimmed)) {
    throw StatusException(
        Status.INVALID_ARGUMENT.withDescription(
            "docker_image '$dockerImage' must be a fully-qualified registry ref " +
                "(e.g. LOCATION-docker.pkg.dev/PROJECT/REPO/IMAGE:TAG)"
        )
    )
  }
  val host = trimmed.substringBefore('/')
  if (!isGoogleRegistryHost(host)) {
    throw StatusException(
        Status.INVALID_ARGUMENT.withDescription(
            "docker_image '$dockerImage' must live in a Google container registry " +
                "(*.pkg.dev, gcr.io, or *.gcr.io) — '$host' is not one. Workload authenticates to " +
                "the image's registry with a token impersonating the profile's target service " +
                "account, so it will only ever send credentials to a Google registry."
        )
    )
  }
}

// A Go-style duration: one or more <number><unit> segments (h/m/s), e.g. "6h", "90m", "1h30m".
// Matches the shape the drain contract expects the supervisor's stop timeout in.
private val drainDeadlinePattern = Regex("""(\d+h)?(\d+m)?(\d+s)?""")

/**
 * Validates a non-blank drain_deadline; a blank value means "supervisor default" and is allowed.
 */
private fun requireValidDrainDeadline(drainDeadline: String) {
  if (drainDeadline.isBlank()) return
  if (!drainDeadlinePattern.matches(drainDeadline)) {
    throw StatusException(
        Status.INVALID_ARGUMENT.withDescription(
            "drain_deadline '$drainDeadline' must be a duration like '6h', '90m', or '1h30m'"
        )
    )
  }
}

private fun notFound(kind: String, id: String): StatusException =
    StatusException(Status.NOT_FOUND.withDescription("$kind '$id' not found"))

private fun failedPrecondition(message: String): StatusException =
    StatusException(Status.FAILED_PRECONDITION.withDescription(message))

private fun permissionDenied(message: String): StatusException =
    StatusException(Status.PERMISSION_DENIED.withDescription(message))

/**
 * Enforces a scoped service principal's profile allowlist (Phase 2 of workload#126). A
 * [Principal.Service] with an empty [Principal.Service.profileScope] is the pre-existing
 * unrestricted s2s admin and passes through unchanged; a non-empty scope must contain [profileId]
 * or the call is rejected. Human principals are never scoped.
 */
private fun requireProfileScope(profileId: ProfileId) {
  val principal = currentAdminPrincipal()
  if (
      principal is Principal.Service &&
          principal.profileScope.isNotEmpty() &&
          profileId.value !in principal.profileScope
  ) {
    throw permissionDenied(
        "service principal '${principal.email}' is not scoped to profile '${profileId.value}'"
    )
  }
}

/**
 * Denies the call outright for a scoped service principal — used by every admin RPC that a
 * profile-update-scoped CI principal has no business calling (worker lifecycle, grants, enrollment
 * tokens, fleet-wide listing). Unscoped services and humans are unaffected.
 */
private fun requireUnscopedPrincipal(rpcName: String) {
  val principal = currentAdminPrincipal()
  if (principal is Principal.Service && principal.profileScope.isNotEmpty()) {
    throw permissionDenied(
        "service principal '${principal.email}' is scoped to profile create/update and may not " +
            "call $rpcName"
    )
  }
}

/**
 * The admin plane: `FleetService`, consumed by the console behind the existing console auth (same
 * decorator as [WorkloadServiceImpl] — not the worker plane, not reachable with worker
 * credentials). Every state-changing call is audit-logged with the acting admin's identity.
 */
class FleetServiceImpl(
    private val fleetStore: FleetStore,
    private val impersonationVerifier: ImpersonationVerifier,
    private val imageDigestResolver: ImageDigestResolver,
    private val fallbackPlacementReconciler: FallbackPlacementReconciler =
        FallbackPlacementReconciler(fleetStore),
) : FleetServiceGrpcKt.FleetServiceCoroutineImplBase() {

  override suspend fun listWorkers(request: ListWorkersRequest): ListWorkersResponse {
    requireUnscopedPrincipal("ListWorkers")
    return ListWorkersResponse.newBuilder()
        .addAllWorkers(fleetStore.listWorkers().map { toProto(it) })
        .build()
  }

  override suspend fun approveWorker(request: ApproveWorkerRequest): ApproveWorkerResponse {
    requireUnscopedPrincipal("ApproveWorker")
    val workerId = parseWorkerId(request.workerId)
    val current = fleetStore.getWorker(workerId) ?: throw notFound("worker", request.workerId)
    if (current.status != WorkerStatus.PENDING) {
      throw failedPrecondition("worker '${request.workerId}' is ${current.status}, not pending")
    }

    val admin = currentAdminEmail()
    val approved =
        fleetStore.approveWorker(workerId, approvedBy = admin)
            ?: throw notFound("worker", request.workerId)
    auditWorkerChange("worker_approved", workerId)
    // A newly-active worker may be the fallback node coming up, or the dedicated node a
    // fallback-eligible profile has been waiting for.
    fallbackPlacementReconciler.reconcileAll()
    return ApproveWorkerResponse.newBuilder().setWorker(toProto(approved)).build()
  }

  override suspend fun rejectWorker(request: RejectWorkerRequest): RejectWorkerResponse {
    requireUnscopedPrincipal("RejectWorker")
    val workerId = parseWorkerId(request.workerId)
    val current = fleetStore.getWorker(workerId) ?: throw notFound("worker", request.workerId)
    if (current.status != WorkerStatus.PENDING) {
      throw failedPrecondition("worker '${request.workerId}' is ${current.status}, not pending")
    }

    val rejected = fleetStore.rejectWorker(workerId) ?: throw notFound("worker", request.workerId)
    auditWorkerChange("worker_rejected", workerId)
    return RejectWorkerResponse.newBuilder().setWorker(toProto(rejected)).build()
  }

  override suspend fun revokeWorker(request: RevokeWorkerRequest): RevokeWorkerResponse {
    requireUnscopedPrincipal("RevokeWorker")
    val workerId = parseWorkerId(request.workerId)
    val current = fleetStore.getWorker(workerId) ?: throw notFound("worker", request.workerId)
    if (current.status != WorkerStatus.ACTIVE) {
      throw failedPrecondition("worker '${request.workerId}' is ${current.status}, not active")
    }

    val revoked = fleetStore.revokeWorker(workerId) ?: throw notFound("worker", request.workerId)
    auditWorkerChange("worker_revoked", workerId)
    // If this was a dedicated (or the fallback) node, fallback-eligible profiles may need to
    // land/move onto the fallback node now that it's gone.
    fallbackPlacementReconciler.reconcileAll()
    return RevokeWorkerResponse.newBuilder().setWorker(toProto(revoked)).build()
  }

  override suspend fun setWorkerPaused(request: SetWorkerPausedRequest): SetWorkerPausedResponse {
    requireUnscopedPrincipal("SetWorkerPaused")
    val workerId = parseWorkerId(request.workerId)
    fleetStore.getWorker(workerId) ?: throw notFound("worker", request.workerId)

    val updated =
        fleetStore.setWorkerPaused(workerId, request.paused)
            ?: throw notFound("worker", request.workerId)
    auditWorkerChange(if (request.paused) "worker_paused" else "worker_served", workerId)
    return SetWorkerPausedResponse.newBuilder().setWorker(toProto(updated)).build()
  }

  override suspend fun setWorkerFallbackNode(
      request: SetWorkerFallbackNodeRequest
  ): SetWorkerFallbackNodeResponse {
    requireUnscopedPrincipal("SetWorkerFallbackNode")
    val workerId = parseWorkerId(request.workerId)
    fleetStore.getWorker(workerId) ?: throw notFound("worker", request.workerId)

    val updated =
        fleetStore.setWorkerFallbackNode(workerId, request.fallbackNode)
            ?: throw notFound("worker", request.workerId)
    auditWorkerChange(
        if (request.fallbackNode) "worker_fallback_node_set" else "worker_fallback_node_unset",
        workerId,
    )
    // The set of fallback-eligible profiles that should run here (or should move off, if this
    // worker just lost the flag) may have changed.
    fallbackPlacementReconciler.reconcileAll()
    return SetWorkerFallbackNodeResponse.newBuilder().setWorker(toProto(updated)).build()
  }

  private suspend fun toProto(worker: Worker): WorkerProto =
      worker.toProto(fleetStore.listGrantedProfileIds(worker.workerId))

  override suspend fun listRuns(request: ListRunsRequest): ListRunsResponse {
    // A scoped principal (the soak-gate job querying session outcomes by worker digest) must name
    // exactly one of its allowed profiles — an unfiltered or out-of-scope query would leak runs
    // across the whole fleet.
    val requestProfileId = request.profileId.ifBlank { null }?.let { parseProfileId(it) }
    requestProfileId?.let { requireProfileScope(it) }
        ?: run {
          val principal = currentAdminPrincipal()
          if (principal is Principal.Service && principal.profileScope.isNotEmpty()) {
            throw permissionDenied(
                "service principal '${principal.email}' must filter ListRuns by an in-scope profile_id"
            )
          }
        }
    val filter =
        RunFilter(
            workerId = request.workerId.ifBlank { null }?.let { parseWorkerId(it) },
            profileId = requestProfileId,
            liveOnly = request.liveOnly,
        )
    val runs = fleetStore.listRuns(filter)
    // Denormalize the worker name for display — one listWorkers, not a lookup per run.
    val workerNames = fleetStore.listWorkers().associate { it.workerId to it.name }
    return ListRunsResponse.newBuilder()
        .addAllRuns(runs.map { it.toProto(workerName = workerNames[it.workerId].orEmpty()) })
        .build()
  }

  override suspend fun listProfiles(request: ListProfilesRequest): ListProfilesResponse {
    requireUnscopedPrincipal("ListProfiles")
    return ListProfilesResponse.newBuilder()
        .addAllProfiles(fleetStore.listProfiles().map { it.toProto() })
        .build()
  }

  override suspend fun createProfile(request: CreateProfileRequest): CreateProfileResponse {
    val profileId = parseProfileId(request.profileId)
    requireProfileScope(profileId)
    requireTargetServiceAccount(request.targetServiceAccount)
    requireValidEnvVars(request.envVarsMap, request.secretEnvVarsMap)
    requireValidImageRef(request.dockerImage)
    requireValidDrainDeadline(request.drainDeadline)
    if (fleetStore.getProfile(profileId) != null) {
      throw StatusException(
          Status.ALREADY_EXISTS.withDescription("Profile '${profileId.value}' already exists")
      )
    }

    val admin = currentAdminEmail()
    // Resolve + CAS-check the image *before* inserting, so a moved-tag rejection creates nothing.
    val imageResolution =
        resolveImageWithCas(
            request.targetServiceAccount,
            request.dockerImage.ifBlank { null },
            request.expectedDockerImageDigest,
        )
    val profile =
        fleetStore.createProfile(
            profileId,
            request.displayName.ifBlank { null },
            NewProfileRevision(
                targetServiceAccount = request.targetServiceAccount,
                createdBy = admin,
                note = request.note.ifBlank { null },
                envVars = request.envVarsMap,
                secretEnvVars = request.secretEnvVarsMap,
                dockerImage = request.dockerImage.ifBlank { null },
                drainDeadline = request.drainDeadline.ifBlank { null },
            ),
        )
    val inserted = fleetStore.getLatestProfileRevision(profileId)!!
    val verified = recordVerification(profileId, inserted)
    val revision =
        imageResolution?.let {
          recordImageResolution(profileId, inserted.revision, inserted.targetServiceAccount, it)
        } ?: verified
    auditProfileChange("profile_created", profileId, revision.revision)
    return CreateProfileResponse.newBuilder()
        .setProfile(profile.toProto())
        .setRevision(revision.toProto())
        .build()
  }

  override suspend fun updateProfile(request: UpdateProfileRequest): UpdateProfileResponse {
    val profileId = parseProfileId(request.profileId)
    requireProfileScope(profileId)
    requireTargetServiceAccount(request.targetServiceAccount)
    requireValidEnvVars(request.envVarsMap, request.secretEnvVarsMap)
    requireValidImageRef(request.dockerImage)
    requireValidDrainDeadline(request.drainDeadline)
    val existing = fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)
    if (existing.archived) {
      throw failedPrecondition("profile '${profileId.value}' is archived")
    }

    val admin = currentAdminEmail()
    val imageResolution =
        resolveImageWithCas(
            request.targetServiceAccount,
            request.dockerImage.ifBlank { null },
            request.expectedDockerImageDigest,
        )
    val appended =
        fleetStore.appendProfileRevision(
            profileId,
            NewProfileRevision(
                targetServiceAccount = request.targetServiceAccount,
                createdBy = admin,
                note = request.note.ifBlank { null },
                envVars = request.envVarsMap,
                secretEnvVars = request.secretEnvVarsMap,
                dockerImage = request.dockerImage.ifBlank { null },
                drainDeadline = request.drainDeadline.ifBlank { null },
            ),
        )
    val verified = recordVerification(profileId, appended)
    val revision =
        imageResolution?.let {
          recordImageResolution(profileId, appended.revision, appended.targetServiceAccount, it)
        } ?: verified
    val profile = fleetStore.getProfile(profileId)!!
    auditProfileChange("profile_updated", profileId, revision.revision)
    return UpdateProfileResponse.newBuilder()
        .setProfile(profile.toProto())
        .setRevision(revision.toProto())
        .build()
  }

  override suspend fun archiveProfile(request: ArchiveProfileRequest): ArchiveProfileResponse {
    requireUnscopedPrincipal("ArchiveProfile")
    val profileId = parseProfileId(request.profileId)
    fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)

    val archived =
        fleetStore.archiveProfile(profileId) ?: throw notFound("profile", request.profileId)
    auditProfileChange("profile_archived", profileId, revision = null)
    // An archived profile is never placed on the fallback node — drop it if it was.
    fallbackPlacementReconciler.reconcile(profileId)
    return ArchiveProfileResponse.newBuilder().setProfile(archived.toProto()).build()
  }

  override suspend fun setProfileFallbackEligible(
      request: SetProfileFallbackEligibleRequest
  ): SetProfileFallbackEligibleResponse {
    val profileId = parseProfileId(request.profileId)
    requireProfileScope(profileId)
    fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)

    val updated =
        fleetStore.setProfileFallbackEligible(profileId, request.fallbackEligible)
            ?: throw notFound("profile", request.profileId)
    auditProfileChange(
        if (request.fallbackEligible) "profile_fallback_eligible_set"
        else "profile_fallback_eligible_unset",
        profileId,
        revision = null,
    )
    fallbackPlacementReconciler.reconcile(profileId)
    return SetProfileFallbackEligibleResponse.newBuilder().setProfile(updated.toProto()).build()
  }

  override suspend fun listProfileRevisions(
      request: ListProfileRevisionsRequest
  ): ListProfileRevisionsResponse {
    val profileId = parseProfileId(request.profileId)
    requireProfileScope(profileId)
    fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)

    return ListProfileRevisionsResponse.newBuilder()
        .addAllRevisions(fleetStore.listProfileRevisions(profileId).map { it.toProto() })
        .build()
  }

  override suspend fun verifyProfile(request: VerifyProfileRequest): VerifyProfileResponse {
    val profileId = parseProfileId(request.profileId)
    requireProfileScope(profileId)
    fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)
    val latest = fleetStore.getLatestProfileRevision(profileId)!!

    // Re-check the (SA, secrets) grants in place — those can change for a fixed revision. But never
    // re-pin an already-resolved digest: a pinned revision is immutable. Only an unresolved image
    // (e.g. the reader grant was just added) gets filled in here.
    val verified = recordVerification(profileId, latest)
    val revision = reResolveImageIfUnpinned(profileId, verified)
    return VerifyProfileResponse.newBuilder().setRevision(revision.toProto()).build()
  }

  override suspend fun resolveImage(request: ResolveImageRequest): ResolveImageResponse {
    requireUnscopedPrincipal("ResolveImage")
    requireTargetServiceAccount(request.targetServiceAccount)
    requireValidImageRef(request.dockerImage)
    if (request.dockerImage.isBlank()) {
      return ResolveImageResponse.newBuilder()
          .setImageStatus(ImageStatus.NOT_APPLICABLE.toProto())
          .build()
    }
    // Same resolution the create path runs, but read-only — nothing is stored. Lets the console
    // show the exact digest (or the unresolvable reason) before an admin commits to pinning it.
    val resolution = imageDigestResolver.resolve(request.targetServiceAccount, request.dockerImage)
    adminAudit(
        AuditLogEntry(
            event = "image_resolution_preview",
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sourceIp = currentSourceIp(),
            targetServiceAccount = request.targetServiceAccount,
            result = resolution.status.name.lowercase(),
            reason = resolution.detail,
        )
    )
    return ResolveImageResponse.newBuilder()
        .setDockerImageDigest(resolution.digest.orEmpty())
        .setImageStatus(resolution.status.toProto())
        .setDetail(resolution.detail.orEmpty())
        .build()
  }

  override suspend fun grantProfile(request: GrantProfileRequest): GrantProfileResponse {
    requireUnscopedPrincipal("GrantProfile")
    val workerId = parseWorkerId(request.workerId)
    val profileId = parseProfileId(request.profileId)
    fleetStore.getWorker(workerId) ?: throw notFound("worker", request.workerId)
    val profile = fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)
    if (profile.archived) {
      throw failedPrecondition("profile '${profileId.value}' is archived")
    }

    val admin = currentAdminEmail()
    fleetStore.grant(workerId, profileId, grantedBy = admin)
    adminAudit(
        AuditLogEntry(
            event = "profile_granted",
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sourceIp = currentSourceIp(),
            workerId = workerId.value.toString(),
            profileId = profileId.value,
            result = "success",
        )
    )
    // A grant to a real (non-fallback) worker is a dedicated node appearing — migrate off fallback
    // if this profile is fallback-eligible and was running there.
    fallbackPlacementReconciler.reconcile(profileId)
    return GrantProfileResponse.getDefaultInstance()
  }

  override suspend fun revokeProfileGrant(
      request: RevokeProfileGrantRequest
  ): RevokeProfileGrantResponse {
    requireUnscopedPrincipal("RevokeProfileGrant")
    val workerId = parseWorkerId(request.workerId)
    val profileId = parseProfileId(request.profileId)
    fleetStore.revoke(workerId, profileId)
    adminAudit(
        AuditLogEntry(
            event = "profile_grant_revoked",
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sourceIp = currentSourceIp(),
            workerId = workerId.value.toString(),
            profileId = profileId.value,
            result = "success",
        )
    )
    // The revoked worker may have been this profile's dedicated node — fall back if so.
    fallbackPlacementReconciler.reconcile(profileId)
    return RevokeProfileGrantResponse.getDefaultInstance()
  }

  override suspend fun listAssignments(request: ListAssignmentsRequest): ListAssignmentsResponse {
    requireUnscopedPrincipal("ListAssignments")
    val workerFilter = request.workerId.ifBlank { null }?.let { parseWorkerId(it) }
    val profileFilter = request.profileId.ifBlank { null }?.let { parseProfileId(it) }
    val assignments =
        fleetStore.listAssignments().filter {
          (workerFilter == null || it.workerId == workerFilter) &&
              (profileFilter == null || it.profileId == profileFilter)
        }
    return ListAssignmentsResponse.newBuilder()
        .addAllAssignments(assignments.map { it.toProto() })
        .build()
  }

  override suspend fun createAssignment(
      request: CreateAssignmentRequest
  ): CreateAssignmentResponse {
    requireUnscopedPrincipal("CreateAssignment")
    val workerId = parseWorkerId(request.workerId)
    val profileId = parseProfileId(request.profileId)
    fleetStore.getWorker(workerId) ?: throw notFound("worker", request.workerId)
    val profile = fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)
    if (profile.archived) {
      throw failedPrecondition("profile '${profileId.value}' is archived")
    }

    val admin = currentAdminEmail()
    val created =
        fleetStore.createAssignment(
            NewAssignment(workerId = workerId, profileId = profileId, createdBy = admin)
        )
    adminAudit(
        AuditLogEntry(
            event = "assignment_created",
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sourceIp = currentSourceIp(),
            workerId = workerId.value.toString(),
            profileId = profileId.value,
            result = "success",
        )
    )
    // A first-class assignment to a real (non-fallback) worker is a dedicated node appearing too —
    // migrate off fallback if this profile is fallback-eligible and was running there.
    fallbackPlacementReconciler.reconcile(profileId)
    return CreateAssignmentResponse.newBuilder().setAssignment(created.toProto()).build()
  }

  override suspend fun deleteAssignment(
      request: DeleteAssignmentRequest
  ): DeleteAssignmentResponse {
    requireUnscopedPrincipal("DeleteAssignment")
    val id = parseAssignmentId(request.assignmentId)
    val deleted =
        fleetStore.deleteAssignment(id) ?: throw notFound("assignment", request.assignmentId)
    adminAudit(
        AuditLogEntry(
            event = "assignment_deleted",
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sourceIp = currentSourceIp(),
            workerId = deleted.workerId.value.toString(),
            profileId = deleted.profileId.value,
            result = "success",
        )
    )
    // The deleted assignment may have been this profile's dedicated node — fall back if so.
    fallbackPlacementReconciler.reconcile(deleted.profileId)
    return DeleteAssignmentResponse.getDefaultInstance()
  }

  override suspend fun createEnrollmentToken(
      request: CreateEnrollmentTokenRequest
  ): CreateEnrollmentTokenResponse {
    requireUnscopedPrincipal("CreateEnrollmentToken")
    val admin = currentAdminEmail()
    val ttlDays =
        if (request.expiresInDays > 0) request.expiresInDays.toLong()
        else defaultEnrollmentTokenTtlDays
    // The plaintext exists only here and in the response — only its hash is ever stored.
    val plaintext = WorkloadToken.generate(TokenKind.ENROLLMENT)
    val created =
        fleetStore.createEnrollmentToken(
            NewEnrollmentToken(
                tokenHash = hashEnrollmentToken(plaintext),
                note = request.note.ifBlank { null },
                createdBy = admin,
                expiresAt = Instant.now().plus(Duration.ofDays(ttlDays)),
                requireApproval = request.requireApproval,
            )
        )
    auditEnrollmentTokenChange("enrollment_token_created", created.id, created.expiresAt)
    return CreateEnrollmentTokenResponse.newBuilder()
        .setToken(plaintext)
        .setEnrollmentToken(created.toProto())
        .build()
  }

  override suspend fun listEnrollmentTokens(
      request: ListEnrollmentTokensRequest
  ): ListEnrollmentTokensResponse {
    requireUnscopedPrincipal("ListEnrollmentTokens")
    return ListEnrollmentTokensResponse.newBuilder()
        .addAllEnrollmentTokens(
            fleetStore.listOutstandingEnrollmentTokens(Instant.now()).map { it.toProto() }
        )
        .build()
  }

  override suspend fun revokeEnrollmentToken(
      request: RevokeEnrollmentTokenRequest
  ): RevokeEnrollmentTokenResponse {
    requireUnscopedPrincipal("RevokeEnrollmentToken")
    val id = parseEnrollmentTokenId(request.enrollmentTokenId)
    // A null return means it was already used, already revoked, or never existed — all indistinct
    // to the admin, and none of them leave anything to revoke.
    fleetStore.revokeEnrollmentToken(id, Instant.now())
        ?: throw notFound("enrollment token", request.enrollmentTokenId)
    auditEnrollmentTokenChange("enrollment_token_revoked", id, expiresAt = null)
    return RevokeEnrollmentTokenResponse.getDefaultInstance()
  }

  /**
   * Dry-run verifies a revision's impersonation + secret access and records the result. This is the
   * part that legitimately changes over the life of a fixed revision (a grant is added or revoked),
   * so it re-runs on every create/update/verify. Image resolution is deliberately NOT here — see
   * [resolveImageWithCas], [recordImageResolution], and [reResolveImageIfUnpinned].
   */
  private suspend fun recordVerification(
      profileId: ProfileId,
      revision: ProfileRevision,
  ): ProfileRevision {
    val result = impersonationVerifier.verify(revision.targetServiceAccount, revision.secretEnvVars)
    adminAudit(
        AuditLogEntry(
            event = "profile_revision_verification",
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sourceIp = currentSourceIp(),
            profileId = profileId.value,
            revision = revision.revision,
            targetServiceAccount = revision.targetServiceAccount,
            result = result.status.name.lowercase(),
            reason = result.detail,
        )
    )
    return fleetStore.recordVerification(profileId, revision.revision, result.status)
        ?: revision.copy(verificationStatus = result.status)
  }

  /**
   * Resolves [dockerImage]'s digest as [targetServiceAccount], enforcing the compare-and-swap
   * contract: if [expectedDigest] is non-blank and the tag no longer resolves to it, the tag moved
   * since the admin previewed it — reject so they can't silently pin something they didn't see. A
   * blank [expectedDigest] means "resolve fresh, no check". Returns null when there's no image.
   *
   * Runs *before* the revision is inserted, so a rejected CAS leaves nothing behind.
   */
  private suspend fun resolveImageWithCas(
      targetServiceAccount: String,
      dockerImage: String?,
      expectedDigest: String,
  ): ImageResolution? {
    if (dockerImage == null) return null
    val resolution = imageDigestResolver.resolve(targetServiceAccount, dockerImage)
    if (expectedDigest.isNotBlank() && expectedDigest != resolution.digest) {
      throw StatusException(
          Status.FAILED_PRECONDITION.withDescription(
              "The image '$dockerImage' now resolves to '${resolution.digest ?: "nothing"}', " +
                  "not the '$expectedDigest' you confirmed — the tag moved since you previewed it. " +
                  "Refresh the resolved digest and try again."
          )
      )
    }
    return resolution
  }

  /**
   * Records an already-computed [ImageResolution] (from [resolveImageWithCas]) on a fresh revision.
   */
  private suspend fun recordImageResolution(
      profileId: ProfileId,
      revision: Int,
      targetServiceAccount: String,
      resolution: ImageResolution,
  ): ProfileRevision {
    adminAudit(
        AuditLogEntry(
            event = "profile_revision_image_resolution",
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sourceIp = currentSourceIp(),
            profileId = profileId.value,
            revision = revision,
            targetServiceAccount = targetServiceAccount,
            result = resolution.status.name.lowercase(),
            reason = resolution.detail,
        )
    )
    return fleetStore.recordImageDigest(profileId, revision, resolution.digest, resolution.status)
        ?: error("revision $revision of ${profileId.value} vanished while recording its digest")
  }

  /**
   * Re-resolves the image only when the revision has **not** already pinned a digest — a `RESOLVED`
   * digest is immutable and must never change (that's the whole point of pinning), but an
   * `UNRESOLVABLE`/`UNDETERMINED` revision has nothing pinned yet, so re-verifying after a grant is
   * fixed can fill it in. Used by VerifyProfile; there is no CAS token on that path.
   */
  private suspend fun reResolveImageIfUnpinned(
      profileId: ProfileId,
      revision: ProfileRevision,
  ): ProfileRevision {
    val imageRef = revision.dockerImage ?: return revision
    if (revision.imageStatus == ImageStatus.RESOLVED) return revision
    val resolution = imageDigestResolver.resolve(revision.targetServiceAccount, imageRef)
    return recordImageResolution(
        profileId,
        revision.revision,
        revision.targetServiceAccount,
        resolution,
    )
  }

  /**
   * Emit an admin-plane audit entry, stamping the verified acting principal (email + kind) so every
   * admin action records who did it and whether they were a human or a service account (M5-05).
   */
  private fun adminAudit(entry: AuditLogEntry) {
    val principal = currentAdminPrincipal()
    audit(
        entry.copy(actor = principal?.email ?: "unknown", actorKind = principal?.kind ?: "unknown")
    )
  }

  private fun auditWorkerChange(event: String, workerId: WorkerId) {
    adminAudit(
        AuditLogEntry(
            event = event,
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sourceIp = currentSourceIp(),
            workerId = workerId.value.toString(),
            reason = "admin:${currentAdminEmail()}",
            result = "success",
        )
    )
  }

  private fun auditProfileChange(event: String, profileId: ProfileId, revision: Int?) {
    adminAudit(
        AuditLogEntry(
            event = event,
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sourceIp = currentSourceIp(),
            profileId = profileId.value,
            revision = revision,
            reason = "admin:${currentAdminEmail()}",
            result = "success",
        )
    )
  }

  private fun auditEnrollmentTokenChange(
      event: String,
      id: EnrollmentTokenId,
      expiresAt: Instant?,
  ) {
    adminAudit(
        AuditLogEntry(
            event = event,
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sourceIp = currentSourceIp(),
            expiresAt = expiresAt?.toString(),
            enrollmentTokenId = id.value.toString(),
            reason = "admin:${currentAdminEmail()}",
            result = "success",
        )
    )
  }
}
