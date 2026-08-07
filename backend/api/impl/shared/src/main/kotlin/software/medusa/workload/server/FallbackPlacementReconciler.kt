package software.medusa.workload.server

/**
 * The `grantedBy`/`createdBy` actor stamped on every grant and [Assignment] this reconciler makes
 * on its own initiative, so an auto-created fallback placement is always distinguishable from one
 * an admin made by hand.
 */
const val fallbackPlacementActor = "system:fallback-placement"

/**
 * Fallback auto-placement (workload#122 part 2, built on the first-class [Assignment] concept from
 * part 1): while a profile is [Profile.fallbackEligible], it is kept running on the shared fallback
 * node — the oldest currently-active worker with [Worker.fallbackNode] set — for as long as no
 * *dedicated* node is present for it, and is migrated off automatically the instant one appears.
 *
 * A dedicated node is any other currently-active worker an admin has placed the profile on, via
 * either a [Grant] ("worker may run profile") or a first-class [Assignment] ("worker is assigned
 * profile") — either primitive counts, since both already express the same admin intent: this
 * worker, specifically, runs this profile.
 *
 * Grants are what actually drive execution — [WorkerAssignmentsService] derives a worker's
 * container set from [FleetStore.listGrantedProfileIds] — so landing a profile on the fallback node
 * means granting it there; migrating off means revoking that grant. Each such grant is mirrored
 * with a matching [Assignment] tagged [fallbackPlacementActor], so the placement decision is also
 * visible through the part-1 assignments API, indistinguishable from any other assignment except by
 * who made it.
 *
 * The fallback node has a fixed [slotCapacity] (workload#122 part 3): at most that many
 * fallback-placed profiles are ever granted onto it at once, so it enforces a bounded number of
 * concurrent placements. A profile that wants the fallback node while it's full still gets its
 * [Assignment] — recording the placement decision and its arrival order via the assignment's
 * `createdAt` — but no [Grant] yet, so [WorkerAssignmentsService] doesn't hand it to the worker.
 * That grant-less assignment *is* the queue entry: an assignment without a grant is waiting, one
 * with a grant occupies a slot. [admitFromQueue] re-derives this on every
 * [reconcile]/[reconcileAll] call — granting the oldest ungranted assignments first, up to capacity
 * — so slots vacated by a migrated-off or unqueued profile are backfilled strictly in arrival
 * order, with no separate queue table to keep in sync.
 *
 * Callers re-run [reconcile]/[reconcileAll] after anything that can change "is a dedicated node
 * present" or "is a slot free": granting/revoking a profile, creating/deleting an assignment, a
 * worker's active status changing, the fallback-node flag changing, or the profile's own
 * fallback-eligible flag changing. There is no background loop — like the rest of this codebase's
 * derived state (run presence, pending-worker expiry), it is recomputed at the moment something
 * could have made it stale.
 */
class FallbackPlacementReconciler(
    private val fleetStore: FleetStore,
    private val slotCapacity: Int = 8,
) {

  init {
    require(slotCapacity > 0) { "slotCapacity must be positive, was $slotCapacity" }
  }

  /** Recomputes fallback placement for a single profile. Idempotent; safe to call freely. */
  suspend fun reconcile(profileId: ProfileId) {
    val profile = fleetStore.getProfile(profileId) ?: return
    val fallbackAssignment =
        fleetStore.listAssignments().find {
          it.profileId == profileId && it.createdBy == fallbackPlacementActor
        }

    val fallbackWorker = fallbackNode()
    val shouldWantFallback =
        profile.fallbackEligible &&
            !profile.archived &&
            fallbackWorker != null &&
            !hasDedicatedNode(profileId, excluding = fallbackWorker.workerId)

    if (!shouldWantFallback) {
      removeFallbackPlacement(fallbackAssignment)
      if (fallbackWorker != null) admitFromQueue(fallbackWorker)
      return
    }
    checkNotNull(fallbackWorker)

    if (fallbackAssignment == null || fallbackAssignment.workerId != fallbackWorker.workerId) {
      // Either the first placement, or the fallback node itself changed — clear any stale
      // placement, then (re-)enter the queue against the current one at the back.
      removeFallbackPlacement(fallbackAssignment)
      fleetStore.createAssignment(
          NewAssignment(
              workerId = fallbackWorker.workerId,
              profileId = profileId,
              createdBy = fallbackPlacementActor,
          )
      )
    }

    // Whether this call just created a queue entry, found one already waiting, or found one
    // already granted, re-derive who currently holds a slot vs. who's still waiting.
    admitFromQueue(fallbackWorker)
  }

  /**
   * Re-evaluates every fallback-eligible profile — for changes that aren't scoped to one profile,
   * e.g. a worker's active status or fallback-node flag changing.
   */
  suspend fun reconcileAll() {
    fleetStore.listProfiles().filter { it.fallbackEligible }.forEach { reconcile(it.profileId) }
  }

  private suspend fun removeFallbackPlacement(assignment: Assignment?) {
    if (assignment == null) return
    fleetStore.revoke(
        assignment.workerId,
        assignment.profileId,
    ) // no-op if only queued, not granted
    fleetStore.deleteAssignment(assignment.id)
  }

  /**
   * Grants a slot on [worker] to every fallback-placed assignment that doesn't have one yet, oldest
   * first, until [slotCapacity] is reached. Already-granted assignments count against capacity but
   * are otherwise left untouched — this only ever admits waiting entries, never evicts a held slot.
   */
  private suspend fun admitFromQueue(worker: Worker) {
    val fallbackAssignmentsOldestFirst =
        fleetStore
            .listAssignments()
            .filter { it.workerId == worker.workerId && it.createdBy == fallbackPlacementActor }
            .sortedBy { it.createdAt }

    var occupiedSlots = 0
    for (assignment in fallbackAssignmentsOldestFirst) {
      if (fleetStore.hasGrant(worker.workerId, assignment.profileId)) {
        occupiedSlots++
        continue
      }
      if (occupiedSlots >= slotCapacity) break // full; this and every later entry keep waiting
      fleetStore.grant(worker.workerId, assignment.profileId, grantedBy = fallbackPlacementActor)
      occupiedSlots++
    }
  }

  /**
   * Whether some active worker other than [excluding] already runs [profileId] by explicit admin
   * placement (a grant or a non-fallback assignment) — i.e. a dedicated node is present. Grants and
   * assignments this reconciler made itself ([fallbackPlacementActor]) never count, regardless of
   * which worker they currently sit on — otherwise a stale fallback grant left on a worker that
   * just lost the fallback-node flag would be mistaken for a dedicated placement.
   */
  private suspend fun hasDedicatedNode(profileId: ProfileId, excluding: WorkerId): Boolean {
    val dedicatedAssignedWorkers =
        fleetStore
            .listAssignments()
            .filter { it.profileId == profileId && it.createdBy != fallbackPlacementActor }
            .map { it.workerId }
            .toSet()
    return fleetStore.listWorkers().any { worker ->
      worker.workerId != excluding &&
          worker.status == WorkerStatus.ACTIVE &&
          (fleetStore.getGrant(worker.workerId, profileId)?.grantedBy.let {
            it != null && it != fallbackPlacementActor
          } || worker.workerId in dedicatedAssignedWorkers)
    }
  }

  /** The shared fallback node: the oldest currently-active worker flagged [Worker.fallbackNode]. */
  private suspend fun fallbackNode(): Worker? =
      fleetStore
          .listWorkers()
          .filter { it.fallbackNode && it.status == WorkerStatus.ACTIVE }
          .minByOrNull { it.createdAt }
}
