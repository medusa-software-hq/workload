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
 * Callers re-run [reconcile]/[reconcileAll] after anything that can change "is a dedicated node
 * present": granting/revoking a profile, creating/deleting an assignment, a worker's active status
 * changing, the fallback-node flag changing, or the profile's own fallback-eligible flag changing.
 * There is no background loop — like the rest of this codebase's derived state (run presence,
 * pending-worker expiry), it is recomputed at the moment something could have made it stale.
 */
class FallbackPlacementReconciler(private val fleetStore: FleetStore) {

  /** Recomputes fallback placement for a single profile. Idempotent; safe to call freely. */
  suspend fun reconcile(profileId: ProfileId) {
    val profile = fleetStore.getProfile(profileId) ?: return
    val fallbackAssignment =
        fleetStore.listAssignments().find {
          it.profileId == profileId && it.createdBy == fallbackPlacementActor
        }

    val fallbackWorker = fallbackNode()
    val shouldRunOnFallback =
        profile.fallbackEligible &&
            !profile.archived &&
            fallbackWorker != null &&
            !hasDedicatedNode(profileId, excluding = fallbackWorker.workerId)

    if (!shouldRunOnFallback) {
      removeFallbackPlacement(fallbackAssignment)
      return
    }
    checkNotNull(fallbackWorker)

    if (fallbackAssignment != null && fallbackAssignment.workerId == fallbackWorker.workerId) {
      return // already correctly placed
    }

    // Either the first placement, or the fallback node itself changed — clear any stale placement
    // before landing on the current one.
    removeFallbackPlacement(fallbackAssignment)
    fleetStore.grant(fallbackWorker.workerId, profileId, grantedBy = fallbackPlacementActor)
    fleetStore.createAssignment(
        NewAssignment(
            workerId = fallbackWorker.workerId,
            profileId = profileId,
            createdBy = fallbackPlacementActor,
        )
    )
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
    fleetStore.revoke(assignment.workerId, assignment.profileId)
    fleetStore.deleteAssignment(assignment.id)
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
