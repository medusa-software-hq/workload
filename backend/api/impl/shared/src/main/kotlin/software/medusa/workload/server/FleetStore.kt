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

  /**
   * Atomically redeems an enrollment token and registers a v2 worker in one step: burns the token
   * (conditional on it being outstanding as of [now]) and, iff that succeeds, creates a worker with
   * the given [secretHash] and metadata — `ACTIVE` when the token was minted without approval,
   * `PENDING` when it required it. Returns the new worker, or null if the token was unknown,
   * already used, revoked, or expired. The burn's conditional write is the race guard, so N
   * concurrent redemptions of one token create at most one worker.
   */
  suspend fun registerWorkerWithEnrollmentToken(
      tokenHash: SecretHash,
      now: Instant,
      secretHash: SecretHash,
      name: String,
      hostname: String?,
      os: String?,
      cliVersion: String?,
      sourceIp: String,
  ): Worker?

  suspend fun getWorker(workerId: WorkerId): Worker?

  suspend fun listWorkers(): List<Worker>

  /** Moves a `pending` worker to `active`, recording who approved it and when. */
  suspend fun approveWorker(workerId: WorkerId, approvedBy: String): Worker?

  suspend fun rejectWorker(workerId: WorkerId): Worker?

  suspend fun revokeWorker(workerId: WorkerId): Worker?

  /**
   * Sets the operator pause/serve switch (M7-05): [paused] true records [now] as `pausedAt`; false
   * clears it. Never touches `status` — a paused worker stays exactly as active/pending/etc as it
   * was. Returns null if the worker doesn't exist.
   */
  suspend fun setWorkerPaused(
      workerId: WorkerId,
      paused: Boolean,
      now: Instant = Instant.now(),
  ): Worker?

  /** Updates `lastSeenAt` to now; a no-op if the worker doesn't exist. */
  suspend fun touchLastSeen(workerId: WorkerId)

  /**
   * Marks/unmarks this worker as (one of) the shared fallback node(s) (workload#122 part 2).
   * Returns null if the worker doesn't exist.
   */
  suspend fun setWorkerFallbackNode(workerId: WorkerId, fallbackNode: Boolean): Worker?

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

  /**
   * Tags/untags this profile for fallback auto-placement (workload#122 part 2). Returns null if the
   * profile doesn't exist.
   */
  suspend fun setProfileFallbackEligible(profileId: ProfileId, fallbackEligible: Boolean): Profile?

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

  /** The grant for this (worker, profile) pair, or null if none exists. */
  suspend fun getGrant(workerId: WorkerId, profileId: ProfileId): Grant?

  suspend fun listGrantedProfileIds(workerId: WorkerId): List<ProfileId>

  // Assignments — a first-class placement record, distinct from a grant (see [Assignment]).

  suspend fun createAssignment(assignment: NewAssignment): Assignment

  /** Returns the deleted assignment, or null if no assignment with that id exists. */
  suspend fun deleteAssignment(id: AssignmentId): Assignment?

  /** All assignments, newest first. */
  suspend fun listAssignments(): List<Assignment>

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

  // Runs (M6-B1)

  /** Records the start of a run; it begins `RUNNING` with its first heartbeat at [now]. */
  suspend fun createRun(run: NewRun, now: Instant = Instant.now()): Run

  /**
   * Moves a still-running run's last heartbeat to [now], returning the (re-derived) run. Returns
   * null if the run doesn't exist or has already ended — a heartbeat can revive a `lost` run but
   * never a terminal one.
   */
  suspend fun heartbeatRun(runId: RunId, now: Instant = Instant.now()): Run?

  /**
   * Ends a still-running run: records [exitCode] (nullable — an abnormal exit whose code is
   * unknown) and sets the stored terminal state via [terminalStateFor]. Returns the ended run, or
   * null if it doesn't exist or already ended. Only the reporting process ever writes a terminal
   * state.
   */
  suspend fun endRun(runId: RunId, exitCode: Int?, now: Instant = Instant.now()): Run?

  /** Reads a single run with its effective state ([deriveRunState]) applied, or null if unknown. */
  suspend fun getRun(runId: RunId, now: Instant = Instant.now()): Run?

  /** Lists runs matching [filter], newest first, each with its effective state applied. */
  suspend fun listRuns(filter: RunFilter, now: Instant = Instant.now()): List<Run>
}
