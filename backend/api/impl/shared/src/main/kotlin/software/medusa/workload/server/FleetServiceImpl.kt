package software.medusa.workload.server

import io.grpc.Status
import io.grpc.StatusException
import java.time.Instant
import java.util.UUID
import software.medusa.workload.v1.ApproveWorkerRequest
import software.medusa.workload.v1.ApproveWorkerResponse
import software.medusa.workload.v1.ArchiveProfileRequest
import software.medusa.workload.v1.ArchiveProfileResponse
import software.medusa.workload.v1.CreateProfileRequest
import software.medusa.workload.v1.CreateProfileResponse
import software.medusa.workload.v1.FleetServiceGrpcKt
import software.medusa.workload.v1.GrantProfileRequest
import software.medusa.workload.v1.GrantProfileResponse
import software.medusa.workload.v1.ImageStatus as ImageStatusProto
import software.medusa.workload.v1.ListProfileRevisionsRequest
import software.medusa.workload.v1.ListProfileRevisionsResponse
import software.medusa.workload.v1.ListProfilesRequest
import software.medusa.workload.v1.ListProfilesResponse
import software.medusa.workload.v1.ListWorkersRequest
import software.medusa.workload.v1.ListWorkersResponse
import software.medusa.workload.v1.Profile as ProfileProto
import software.medusa.workload.v1.ProfileRevision as ProfileRevisionProto
import software.medusa.workload.v1.RejectWorkerRequest
import software.medusa.workload.v1.RejectWorkerResponse
import software.medusa.workload.v1.RevokeProfileGrantRequest
import software.medusa.workload.v1.RevokeProfileGrantResponse
import software.medusa.workload.v1.RevokeWorkerRequest
import software.medusa.workload.v1.RevokeWorkerResponse
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
        // Confirmation code is only meaningful (and only ever shown) while pending.
        .setConfirmationCode(if (status == WorkerStatus.PENDING) confirmationCode.orEmpty() else "")
        .setCreatedAt(createdAt.toString())
        .setApprovedAt(approvedAt?.toString().orEmpty())
        .setApprovedBy(approvedBy.orEmpty())
        .setLastSeenAt(lastSeenAt?.toString().orEmpty())
        .addAllGrantedProfileIds(grantedProfileIds.map { it.value })
        .build()

private fun Profile.toProto(): ProfileProto =
    ProfileProto.newBuilder()
        .setProfileId(profileId.value)
        .setDisplayName(displayName.orEmpty())
        .setLatestRevision(latestRevision)
        .setArchived(archived)
        .setCreatedAt(createdAt.toString())
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
        .build()

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

/** Validates a non-blank image ref; a blank ref means "no image" and is allowed. */
private fun requireValidImageRef(dockerImage: String) {
  if (dockerImage.isBlank()) return
  if (!imageRefPattern.matches(dockerImage.trim())) {
    throw StatusException(
        Status.INVALID_ARGUMENT.withDescription(
            "docker_image '$dockerImage' must be a fully-qualified registry ref " +
                "(e.g. LOCATION-docker.pkg.dev/PROJECT/REPO/IMAGE:TAG)"
        )
    )
  }
}

private fun notFound(kind: String, id: String): StatusException =
    StatusException(Status.NOT_FOUND.withDescription("$kind '$id' not found"))

private fun failedPrecondition(message: String): StatusException =
    StatusException(Status.FAILED_PRECONDITION.withDescription(message))

/**
 * The admin plane: `FleetService`, consumed by the console behind the existing console auth (same
 * decorator as [WorkloadServiceImpl] — not the worker plane, not reachable with worker
 * credentials). Every state-changing call is audit-logged with the acting admin's identity.
 */
class FleetServiceImpl(
    private val fleetStore: FleetStore,
    private val impersonationVerifier: ImpersonationVerifier,
    private val imageDigestResolver: ImageDigestResolver,
) : FleetServiceGrpcKt.FleetServiceCoroutineImplBase() {

  override suspend fun listWorkers(request: ListWorkersRequest): ListWorkersResponse =
      ListWorkersResponse.newBuilder()
          .addAllWorkers(fleetStore.listWorkers().map { toProto(it) })
          .build()

  override suspend fun approveWorker(request: ApproveWorkerRequest): ApproveWorkerResponse {
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
    return ApproveWorkerResponse.newBuilder().setWorker(toProto(approved)).build()
  }

  override suspend fun rejectWorker(request: RejectWorkerRequest): RejectWorkerResponse {
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
    val workerId = parseWorkerId(request.workerId)
    val current = fleetStore.getWorker(workerId) ?: throw notFound("worker", request.workerId)
    if (current.status != WorkerStatus.ACTIVE) {
      throw failedPrecondition("worker '${request.workerId}' is ${current.status}, not active")
    }

    val revoked = fleetStore.revokeWorker(workerId) ?: throw notFound("worker", request.workerId)
    auditWorkerChange("worker_revoked", workerId)
    return RevokeWorkerResponse.newBuilder().setWorker(toProto(revoked)).build()
  }

  private suspend fun toProto(worker: Worker): WorkerProto =
      worker.toProto(fleetStore.listGrantedProfileIds(worker.workerId))

  override suspend fun listProfiles(request: ListProfilesRequest): ListProfilesResponse =
      ListProfilesResponse.newBuilder()
          .addAllProfiles(fleetStore.listProfiles().map { it.toProto() })
          .build()

  override suspend fun createProfile(request: CreateProfileRequest): CreateProfileResponse {
    val profileId = parseProfileId(request.profileId)
    requireTargetServiceAccount(request.targetServiceAccount)
    requireValidEnvVars(request.envVarsMap, request.secretEnvVarsMap)
    requireValidImageRef(request.dockerImage)
    if (fleetStore.getProfile(profileId) != null) {
      throw StatusException(
          Status.ALREADY_EXISTS.withDescription("Profile '${profileId.value}' already exists")
      )
    }

    val admin = currentAdminEmail()
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
            ),
        )
    val revision = verifyAndRecord(profileId, fleetStore.getLatestProfileRevision(profileId)!!)
    auditProfileChange("profile_created", profileId, revision.revision)
    return CreateProfileResponse.newBuilder()
        .setProfile(profile.toProto())
        .setRevision(revision.toProto())
        .build()
  }

  override suspend fun updateProfile(request: UpdateProfileRequest): UpdateProfileResponse {
    val profileId = parseProfileId(request.profileId)
    requireTargetServiceAccount(request.targetServiceAccount)
    requireValidEnvVars(request.envVarsMap, request.secretEnvVarsMap)
    requireValidImageRef(request.dockerImage)
    val existing = fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)
    if (existing.archived) {
      throw failedPrecondition("profile '${profileId.value}' is archived")
    }

    val admin = currentAdminEmail()
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
            ),
        )
    val revision = verifyAndRecord(profileId, appended)
    val profile = fleetStore.getProfile(profileId)!!
    auditProfileChange("profile_updated", profileId, revision.revision)
    return UpdateProfileResponse.newBuilder()
        .setProfile(profile.toProto())
        .setRevision(revision.toProto())
        .build()
  }

  override suspend fun archiveProfile(request: ArchiveProfileRequest): ArchiveProfileResponse {
    val profileId = parseProfileId(request.profileId)
    fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)

    val archived =
        fleetStore.archiveProfile(profileId) ?: throw notFound("profile", request.profileId)
    auditProfileChange("profile_archived", profileId, revision = null)
    return ArchiveProfileResponse.newBuilder().setProfile(archived.toProto()).build()
  }

  override suspend fun listProfileRevisions(
      request: ListProfileRevisionsRequest
  ): ListProfileRevisionsResponse {
    val profileId = parseProfileId(request.profileId)
    fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)

    return ListProfileRevisionsResponse.newBuilder()
        .addAllRevisions(fleetStore.listProfileRevisions(profileId).map { it.toProto() })
        .build()
  }

  override suspend fun verifyProfile(request: VerifyProfileRequest): VerifyProfileResponse {
    val profileId = parseProfileId(request.profileId)
    fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)
    val latest = fleetStore.getLatestProfileRevision(profileId)!!

    val revision = verifyAndRecord(profileId, latest)
    return VerifyProfileResponse.newBuilder().setRevision(revision.toProto()).build()
  }

  override suspend fun grantProfile(request: GrantProfileRequest): GrantProfileResponse {
    val workerId = parseWorkerId(request.workerId)
    val profileId = parseProfileId(request.profileId)
    fleetStore.getWorker(workerId) ?: throw notFound("worker", request.workerId)
    val profile = fleetStore.getProfile(profileId) ?: throw notFound("profile", request.profileId)
    if (profile.archived) {
      throw failedPrecondition("profile '${profileId.value}' is archived")
    }

    val admin = currentAdminEmail()
    fleetStore.grant(workerId, profileId, grantedBy = admin)
    audit(
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
    return GrantProfileResponse.getDefaultInstance()
  }

  override suspend fun revokeProfileGrant(
      request: RevokeProfileGrantRequest
  ): RevokeProfileGrantResponse {
    val workerId = parseWorkerId(request.workerId)
    val profileId = parseProfileId(request.profileId)
    fleetStore.revoke(workerId, profileId)
    audit(
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
    return RevokeProfileGrantResponse.getDefaultInstance()
  }

  /**
   * Dry-run verifies [revision] (SA impersonation + secret access) and, if it carries an image,
   * resolves that image's digest — persisting and audit-logging both outcomes. Returns the revision
   * reflecting both records.
   */
  private suspend fun verifyAndRecord(
      profileId: ProfileId,
      revision: ProfileRevision,
  ): ProfileRevision {
    val result = impersonationVerifier.verify(revision.targetServiceAccount, revision.secretEnvVars)
    audit(
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
    val verified =
        fleetStore.recordVerification(profileId, revision.revision, result.status)
            ?: revision.copy(verificationStatus = result.status)
    return resolveImageAndRecord(profileId, verified)
  }

  /**
   * Resolves [revision]'s image tag to a digest (impersonating its target SA) and records the
   * verdict; a no-op that returns the revision unchanged when it has no image. Audit-logs the
   * outcome without leaking the resolved digest beyond the diagnostic detail.
   */
  private suspend fun resolveImageAndRecord(
      profileId: ProfileId,
      revision: ProfileRevision,
  ): ProfileRevision {
    val imageRef = revision.dockerImage ?: return revision
    val resolution = imageDigestResolver.resolve(revision.targetServiceAccount, imageRef)
    audit(
        AuditLogEntry(
            event = "profile_revision_image_resolution",
            requestId = UUID.randomUUID().toString(),
            timestamp = Instant.now().toString(),
            sourceIp = currentSourceIp(),
            profileId = profileId.value,
            revision = revision.revision,
            targetServiceAccount = revision.targetServiceAccount,
            result = resolution.status.name.lowercase(),
            reason = resolution.detail,
        )
    )
    return fleetStore.recordImageDigest(
        profileId,
        revision.revision,
        resolution.digest,
        resolution.status,
    ) ?: revision.copy(dockerImageDigest = resolution.digest, imageStatus = resolution.status)
  }

  private fun auditWorkerChange(event: String, workerId: WorkerId) {
    audit(
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
    audit(
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
}
