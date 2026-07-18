package software.medusa.workload.server

import java.time.Instant

/**
 * Persistence for workers, profiles/revisions, and (worker, profile) grants.
 *
 * Two implementations exist, both required to pass the same contract test suite
 * (`FleetStoreContractTest`): [InMemoryFleetStore] for local dev/tests, [PostgresFleetStore] for
 * production.
 */
interface FleetStore {
  // Workers

  suspend fun createWorker(worker: NewWorker): Worker

  suspend fun getWorker(workerId: WorkerId): Worker?

  suspend fun listWorkers(): List<Worker>

  /** Moves a `pending` worker to `active`, recording who approved it and when. */
  suspend fun approveWorker(workerId: WorkerId, approvedBy: String): Worker?

  suspend fun rejectWorker(workerId: WorkerId): Worker?

  suspend fun revokeWorker(workerId: WorkerId): Worker?

  /** Updates `lastSeenAt` to now; a no-op if the worker doesn't exist. */
  suspend fun touchLastSeen(workerId: WorkerId)

  // Profiles + revisions

  /** Creates a profile together with its first revision — a profile never exists without one. */
  suspend fun createProfile(
      profileId: ProfileId,
      displayName: String?,
      revision: NewProfileRevision,
  ): Profile

  /** Appends revision `latestRevision + 1`, atomically bumping the profile's `latestRevision`. */
  suspend fun appendProfileRevision(
      profileId: ProfileId,
      revision: NewProfileRevision,
  ): ProfileRevision

  suspend fun archiveProfile(profileId: ProfileId): Profile?

  suspend fun getProfile(profileId: ProfileId): Profile?

  suspend fun listProfiles(): List<Profile>

  suspend fun listProfileRevisions(profileId: ProfileId): List<ProfileRevision>

  suspend fun getLatestProfileRevision(profileId: ProfileId): ProfileRevision?

  /** Records the result of a dry-run impersonation check for a specific revision. */
  suspend fun recordVerification(
      profileId: ProfileId,
      revision: Int,
      status: VerificationStatus,
  ): ProfileRevision?

  /**
   * Records the outcome of resolving a revision's image tag to a digest: the pinned [digest] (null
   * when unresolved) and the [status] verdict. Named `record…`, not `update…Revision…`, to stay
   * within the store's no-mutable-revision contract (see the guard in `FleetStoreContractTest`).
   */
  suspend fun recordImageDigest(
      profileId: ProfileId,
      revision: Int,
      digest: String?,
      status: ImageStatus,
  ): ProfileRevision?

  // Grants

  suspend fun grant(workerId: WorkerId, profileId: ProfileId, grantedBy: String): Grant

  suspend fun revoke(workerId: WorkerId, profileId: ProfileId)

  suspend fun hasGrant(workerId: WorkerId, profileId: ProfileId): Boolean

  suspend fun listGrantedProfileIds(workerId: WorkerId): List<ProfileId>

  // Enrollment tokens

  suspend fun createEnrollmentToken(token: NewEnrollmentToken): EnrollmentToken

  /** Outstanding = never redeemed, never revoked, and not expired as of [now]; newest first. */
  suspend fun listOutstandingEnrollmentTokens(now: Instant): List<EnrollmentToken>

  /**
   * Revokes an outstanding token. Returns the revoked token, or null if it doesn't exist or was
   * already used/revoked.
   */
  suspend fun revokeEnrollmentToken(id: EnrollmentTokenId, now: Instant): EnrollmentToken?

  /**
   * Atomically redeems (burns) the token whose hash is [tokenHash], if it is outstanding as of
   * [now]. Returns the burnt token on success, or null if it's unknown, already used, revoked, or
   * expired. The single conditional write is the race guard: given concurrent redemptions of one
   * token, exactly one succeeds.
   */
  suspend fun burnEnrollmentToken(
      tokenHash: SecretHash,
      workerId: WorkerId,
      now: Instant,
  ): EnrollmentToken?
}
