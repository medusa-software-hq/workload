package software.medusa.workload.runtime

import software.medusa.workload.docker.ContainerSummary
import software.medusa.workload.docker.DockerConnector

/**
 * `workload-agent`'s reconciliation core (M7-05): converge this node's running containers onto a
 * fetched assignment set, adopting whatever's already running on restart instead of orphaning or
 * double-starting it.
 *
 * Marks a container as started and owned by the agent's reconciliation loop, distinct from one a
 * human started in the foreground via `workload worker run`/`exec`. Both carry
 * [workloadProfileLabel]/[workloadRevisionLabel] (so `workload ps`/`prune` still see every workload
 * container uniformly), but only an agent-managed one carries this — the diff below never touches a
 * foreground run, and a foreground run is never mistaken for an assignment to adopt.
 */
const val workloadAgentManagedLabel = "ms-workload.agent-managed"

/** One profile the agent should keep a container running for, resolved from the assignment set. */
data class DesiredAssignment(
    val profileId: String,
    val revision: Int,
    val dockerImageDigest: String,
)

/**
 * What [diffAssignments] decided: containers to bring up, and agent-managed containers to remove.
 */
data class ReconcilePlan(
    val toStart: List<DesiredAssignment>,
    val toStop: List<ContainerSummary>,
)

private fun ContainerSummary.assignedProfileId(): String? = labels[workloadProfileLabel]

private fun ContainerSummary.assignedRevision(): Int? = labels[workloadRevisionLabel]?.toIntOrNull()

/**
 * Pure decision function: what changes make the agent-managed containers in [running] match
 * [desired]. [running] must already be filtered to agent-managed containers (label
 * [workloadAgentManagedLabel]) — the caller does the Docker `list` call, this function makes no I/O
 * so it's cheap to test exhaustively.
 *
 * **Adoption is a side effect of doing nothing.** A container already running at its assigned
 * profile+revision matches neither [ReconcilePlan.toStart] nor [ReconcilePlan.toStop] — it's simply
 * left alone. That is the whole adoption mechanism: a freshly restarted agent process computes the
 * exact same plan against the exact same Docker state a running one would, so it converges onto
 * whatever the previous process left behind instead of tearing it down and re-creating it. No
 * separate "adopt" pass, no local state file to go stale.
 *
 * A stopped (exited/crashed) container is always in [ReconcilePlan.toStop] regardless of whether
 * its revision still matches — it isn't serving anything, so it's cleaned up and (if still desired)
 * re-created fresh rather than resumed in place. A container whose revision has moved on is stopped
 * *and* a fresh one for the new revision is started — the caller is expected to apply
 * [ReconcilePlan.toStop] before [ReconcilePlan.toStart] so two revisions of one profile are never
 * up at once.
 */
fun diffAssignments(
    desired: List<DesiredAssignment>,
    running: List<ContainerSummary>,
): ReconcilePlan {
  val desiredByProfile = desired.associateBy { it.profileId }

  val toStart = desired.filter { d ->
    val current = running.find { it.assignedProfileId() == d.profileId }
    current == null || !current.running || current.assignedRevision() != d.revision
  }
  val toStop = running.filter { c ->
    val d = c.assignedProfileId()?.let { desiredByProfile[it] }
    !c.running || d == null || d.revision != c.assignedRevision()
  }
  return ReconcilePlan(toStart, toStop)
}

/** Grace given an agent-managed container before SIGKILL — same as a foreground run's. */
val agentReconcileStopGrace = containerStopGrace

/**
 * Stops and removes every container in [plan]'s [ReconcilePlan.toStop] — best-effort per container,
 * a single failure never blocks the rest. Always runs before [startAssignments] so a superseded
 * revision is gone before its replacement is created.
 */
suspend fun stopSuperseded(
    connector: DockerConnector,
    plan: ReconcilePlan,
    warn: (String) -> Unit,
) {
  for (container in plan.toStop) {
    runCatching {
          if (container.running) {
            connector.containers.stop(container.id, agentReconcileStopGrace)
          }
          connector.containers.remove(container.id, force = true)
        }
        .onFailure {
          warn("workload-agent: could not stop ${container.id.take(12)}: ${it.reason()}")
        }
  }
}

/**
 * Starts a plain (no command-line, no metadata-sidecar) detached container for [assignment]: pulls
 * the pinned digest with the brokered token, creates it labelled agent-managed, and starts it —
 * returning the new container id, or null (warning) on failure so one bad assignment never blocks
 * the rest of the reconcile pass.
 *
 * **Scope note.** Unlike `workload worker run`, this does not yet wire the Beacon metadata-sidecar
 * (see `MetadataSidecar.kt`) — [claim]'s resolved env/secret values are injected directly instead
 * of a refreshing token pointer. Long-lived agent-managed workloads that need GCP access longer
 * than one token's lifetime are a follow-up (the sidecar's per-run network naming would need to
 * become per-assignment and idempotent across restarts to fit this reconcile loop; out of scope
 * here).
 */
suspend fun startAssignment(
    connector: DockerConnector,
    workerId: String,
    assignment: DesiredAssignment,
    claim: WorkerClaimResponse,
    secretValues: Map<String, String>,
    warn: (String) -> Unit,
): String? {
  val image =
      claim.image
          ?: return null.also {
            warn("workload-agent: '${assignment.profileId}' has no image; skipping")
          }
  val pinnedRef = pinnedImageRef(image)
  return runCatching {
        pullBrokeredImage(connector, pinnedRef, claim.accessToken)
        val labels = containerLabels(claim, workerId) + (workloadAgentManagedLabel to "true")
        val created =
            connector.containers.create(
                image = pinnedRef,
                env = (claim.envVars + secretValues).map { (k, v) -> "$k=$v" },
                labels = labels,
                autoRemove = false,
            )
        connector.containers.start(created.id)
        created.id
      }
      .getOrElse {
        warn("workload-agent: could not start '${assignment.profileId}': ${it.reason()}")
        null
      }
}
