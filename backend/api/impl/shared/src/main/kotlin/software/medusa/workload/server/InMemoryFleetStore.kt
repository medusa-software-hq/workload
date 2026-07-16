package software.medusa.workload.server

import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class InMemoryFleetStore : FleetStore {
  private val workers = ConcurrentHashMap<WorkerId, Worker>()
  private val profiles = ConcurrentHashMap<ProfileId, Profile>()
  private val revisions = ConcurrentHashMap<ProfileId, MutableList<ProfileRevision>>()
  private val grants = ConcurrentHashMap<Pair<WorkerId, ProfileId>, Grant>()

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
}
