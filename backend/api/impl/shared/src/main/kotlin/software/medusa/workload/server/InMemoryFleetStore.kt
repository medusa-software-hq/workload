package software.medusa.workload.server

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryFleetStore : FleetStore {
  private val workers = ConcurrentHashMap<WorkerId, Worker>()
  private val profiles = ConcurrentHashMap<ProfileId, Profile>()
  private val revisions = ConcurrentHashMap<ProfileId, MutableList<ProfileRevision>>()
  private val grants = ConcurrentHashMap<Pair<WorkerId, ProfileId>, Grant>()
  private val enrollmentTokens = ConcurrentHashMap<EnrollmentTokenId, EnrollmentToken>()

  override suspend fun createWorker(worker: NewWorker): Worker {
    val created =
        Worker(
            workerId = WorkerId(UUID.randomUUID()),
            secretHash = worker.secretHash,
            name = worker.name,
            hostname = worker.hostname,
            os = worker.os,
            cliVersion = worker.cliVersion,
            status = WorkerStatus.PENDING,
            confirmationCode = worker.confirmationCode,
            createdAt = Instant.now(),
            approvedAt = null,
            approvedBy = null,
            lastSeenAt = null,
            registeredVia = RegisteredVia.V1,
            sourceIp = null,
        )
    workers[created.workerId] = created
    return created
  }

  override suspend fun registerWorkerWithEnrollmentToken(
      tokenHash: SecretHash,
      now: Instant,
      secretHash: SecretHash,
      name: String,
      hostname: String?,
      os: String?,
      cliVersion: String?,
      sourceIp: String,
  ): Worker? {
    // The burn is the serialization point: it atomically selects exactly one winner among
    // concurrent redemptions of the same token, so only that caller goes on to create a worker.
    val workerId = WorkerId(UUID.randomUUID())
    val burnt = burnEnrollmentToken(tokenHash, workerId, now) ?: return null
    val created =
        Worker(
            workerId = workerId,
            secretHash = secretHash,
            name = name,
            hostname = hostname,
            os = os,
            cliVersion = cliVersion,
            status = if (burnt.requireApproval) WorkerStatus.PENDING else WorkerStatus.ACTIVE,
            confirmationCode = null,
            createdAt = now,
            approvedAt = null,
            approvedBy = null,
            lastSeenAt = null,
            registeredVia = RegisteredVia.V2,
            sourceIp = sourceIp,
        )
    workers[created.workerId] = created
    return created
  }

  override suspend fun getWorker(workerId: WorkerId): Worker? =
      workers[workerId]?.let { applyPendingExpiry(it) }

  override suspend fun listWorkers(): List<Worker> =
      workers.values.map { applyPendingExpiry(it) }.sortedBy { it.createdAt }

  override suspend fun approveWorker(workerId: WorkerId, approvedBy: String): Worker? =
      workers.computeIfPresent(workerId) { _, worker ->
        worker.copy(
            status = WorkerStatus.ACTIVE,
            approvedAt = Instant.now(),
            approvedBy = approvedBy,
        )
      }

  override suspend fun rejectWorker(workerId: WorkerId): Worker? =
      workers.computeIfPresent(workerId) { _, worker ->
        worker.copy(status = WorkerStatus.REJECTED)
      }

  override suspend fun revokeWorker(workerId: WorkerId): Worker? =
      workers.computeIfPresent(workerId) { _, worker -> worker.copy(status = WorkerStatus.REVOKED) }

  override suspend fun touchLastSeen(workerId: WorkerId) {
    workers.computeIfPresent(workerId) { _, worker -> worker.copy(lastSeenAt = Instant.now()) }
  }

  override suspend fun createProfile(
      profileId: ProfileId,
      displayName: String?,
      revision: NewProfileRevision,
  ): Profile {
    check(!profiles.containsKey(profileId)) { "Profile '${profileId.value}' already exists" }

    val profile =
        Profile(
            profileId = profileId,
            displayName = displayName,
            latestRevision = 1,
            archived = false,
            createdAt = Instant.now(),
        )
    val firstRevision =
        ProfileRevision(
            profileId = profileId,
            revision = 1,
            targetServiceAccount = revision.targetServiceAccount,
            createdAt = Instant.now(),
            createdBy = revision.createdBy,
            note = revision.note,
            envVars = revision.envVars,
            secretEnvVars = revision.secretEnvVars,
            dockerImage = revision.dockerImage,
            imageStatus = initialImageStatus(revision.dockerImage),
        )
    revisions[profileId] = mutableListOf(firstRevision)
    profiles[profileId] = profile
    return profile
  }

  override suspend fun appendProfileRevision(
      profileId: ProfileId,
      revision: NewProfileRevision,
  ): ProfileRevision {
    val profileRevisions =
        revisions[profileId] ?: error("Profile '${profileId.value}' does not exist")

    synchronized(profileRevisions) {
      val nextRevisionNumber = profileRevisions.size + 1
      val newRevision =
          ProfileRevision(
              profileId = profileId,
              revision = nextRevisionNumber,
              targetServiceAccount = revision.targetServiceAccount,
              createdAt = Instant.now(),
              createdBy = revision.createdBy,
              note = revision.note,
              envVars = revision.envVars,
              secretEnvVars = revision.secretEnvVars,
              dockerImage = revision.dockerImage,
              imageStatus = initialImageStatus(revision.dockerImage),
          )
      profileRevisions.add(newRevision)
      profiles.computeIfPresent(profileId) { _, profile ->
        profile.copy(latestRevision = nextRevisionNumber)
      }
      return newRevision
    }
  }

  override suspend fun archiveProfile(profileId: ProfileId): Profile? =
      profiles.computeIfPresent(profileId) { _, profile -> profile.copy(archived = true) }

  override suspend fun getProfile(profileId: ProfileId): Profile? = profiles[profileId]

  override suspend fun listProfiles(): List<Profile> =
      profiles.values.sortedBy { it.profileId.value }

  override suspend fun listProfileRevisions(profileId: ProfileId): List<ProfileRevision> =
      revisions[profileId]?.let { list -> synchronized(list) { list.toList() } } ?: emptyList()

  override suspend fun getLatestProfileRevision(profileId: ProfileId): ProfileRevision? {
    val profile = profiles[profileId] ?: return null
    val list = revisions[profileId] ?: return null
    return synchronized(list) { list.find { it.revision == profile.latestRevision } }
  }

  override suspend fun recordVerification(
      profileId: ProfileId,
      revision: Int,
      status: VerificationStatus,
  ): ProfileRevision? {
    val list = revisions[profileId] ?: return null
    return synchronized(list) {
      val index = list.indexOfFirst { it.revision == revision }
      if (index < 0) return null
      val updated = list[index].copy(verificationStatus = status)
      list[index] = updated
      updated
    }
  }

  override suspend fun recordImageDigest(
      profileId: ProfileId,
      revision: Int,
      digest: String?,
      status: ImageStatus,
  ): ProfileRevision? {
    val list = revisions[profileId] ?: return null
    return synchronized(list) {
      val index = list.indexOfFirst { it.revision == revision }
      if (index < 0) return null
      val updated = list[index].copy(dockerImageDigest = digest, imageStatus = status)
      list[index] = updated
      updated
    }
  }

  override suspend fun grant(workerId: WorkerId, profileId: ProfileId, grantedBy: String): Grant {
    val grantEntry = Grant(workerId, profileId, Instant.now(), grantedBy)
    grants[workerId to profileId] = grantEntry
    return grantEntry
  }

  override suspend fun revoke(workerId: WorkerId, profileId: ProfileId) {
    grants.remove(workerId to profileId)
  }

  override suspend fun hasGrant(workerId: WorkerId, profileId: ProfileId): Boolean =
      grants.containsKey(workerId to profileId)

  override suspend fun listGrantedProfileIds(workerId: WorkerId): List<ProfileId> =
      grants.keys.filter { it.first == workerId }.map { it.second }.sortedBy { it.value }

  override suspend fun createEnrollmentToken(token: NewEnrollmentToken): EnrollmentToken {
    val created =
        EnrollmentToken(
            id = EnrollmentTokenId(UUID.randomUUID()),
            tokenHash = token.tokenHash,
            note = token.note,
            createdBy = token.createdBy,
            createdAt = Instant.now(),
            expiresAt = token.expiresAt,
            requireApproval = token.requireApproval,
            usedAt = null,
            usedByWorkerId = null,
            revokedAt = null,
        )
    enrollmentTokens[created.id] = created
    return created
  }

  override suspend fun listOutstandingEnrollmentTokens(now: Instant): List<EnrollmentToken> =
      enrollmentTokens.values
          .filter { it.usedAt == null && it.revokedAt == null && it.expiresAt.isAfter(now) }
          .sortedByDescending { it.createdAt }

  override suspend fun revokeEnrollmentToken(
      id: EnrollmentTokenId,
      now: Instant,
  ): EnrollmentToken? {
    var revoked: EnrollmentToken? = null
    enrollmentTokens.computeIfPresent(id) { _, token ->
      if (token.usedAt == null && token.revokedAt == null) {
        token.copy(revokedAt = now).also { revoked = it }
      } else {
        token
      }
    }
    return revoked
  }

  override suspend fun burnEnrollmentToken(
      tokenHash: SecretHash,
      workerId: WorkerId,
      now: Instant,
  ): EnrollmentToken? {
    // Find the row by hash, then burn it atomically under its key: computeIfPresent applies the
    // remap for a given key atomically, so concurrent redemptions of one token serialize and only
    // the first (which still sees it outstanding) sets `burned`.
    val id =
        enrollmentTokens.entries.firstOrNull { it.value.tokenHash == tokenHash }?.key ?: return null
    var burned: EnrollmentToken? = null
    enrollmentTokens.computeIfPresent(id) { _, token ->
      if (token.usedAt == null && token.revokedAt == null && token.expiresAt.isAfter(now)) {
        token.copy(usedAt = now, usedByWorkerId = workerId).also { burned = it }
      } else {
        token
      }
    }
    return burned
  }
}
