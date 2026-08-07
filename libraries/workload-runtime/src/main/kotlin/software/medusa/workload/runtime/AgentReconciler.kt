package software.medusa.workload.runtime

import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration
import software.medusa.workload.docker.ContainerSummary
import software.medusa.workload.docker.DockerConnector

/**
 * `workload-agent`'s reconciliation core (M7-05, amended for the M7 automated-rollout drain
 * contract): converge this node's running containers onto a fetched assignment set, adopting
 * whatever's already running on restart instead of orphaning or double-starting it.
 *
 * Marks a container as started and owned by the agent's reconciliation loop, distinct from one a
 * human started in the foreground via `workload worker run`/`exec`. Both carry
 * [workloadProfileLabel]/[workloadRevisionLabel]/[workloadImageDigestLabel] (so `workload ps`/
 * `prune` still see every workload container uniformly), but only an agent-managed one carries this
 * — the plan below never touches a foreground run, and a foreground run is never mistaken for an
 * assignment to adopt.
 */
const val workloadAgentManagedLabel = "ms-workload.agent-managed"

/**
 * The drain deadline a container was created with, baked in at start time (from that assignment's
 * [DesiredAssignment.drainDeadline]) since Docker has no way to attach it to the container later —
 * unlike [workloadImageDigestLabel]/[workloadProfileLabel]/[workloadRevisionLabel], this can't be
 * recomputed from the *current* assignment set once a container is superseded or unassigned, so it
 * has to travel with the container from the start.
 */
const val workloadDrainDeadlineSecondsLabel = "ms-workload.drain-deadline-seconds"

/** One profile the agent should keep a container running for, resolved from the assignment set. */
data class DesiredAssignment(
    val profileId: String,
    val revision: Int,
    val dockerImageDigest: String,
    // The payload-declared stop timeout for this revision (`ProfileRevision.drainDeadline`, parsed
    // — see [parseDrainDeadline]). Null means "use the supervisor's own default"
    // ([agentReconcileStopGrace]).
    val drainDeadline: Duration? = null,
)

/**
 * Grace given an agent-managed container before SIGKILL when no profile-specific deadline applies —
 * the supervisor's own default. Same value as a foreground run's Ctrl-C grace.
 */
val agentReconcileStopGrace = containerStopGrace

private fun ContainerSummary.assignedProfileId(): String? = labels[workloadProfileLabel]

private fun ContainerSummary.assignedRevision(): Int? = labels[workloadRevisionLabel]?.toIntOrNull()

private fun ContainerSummary.assignedDigest(): String? = labels[workloadImageDigestLabel]

private fun ContainerSummary.configuredDrainDeadline(default: Duration): Duration =
    labels[workloadDrainDeadlineSecondsLabel]?.toLongOrNull()?.seconds ?: default

private fun ContainerSummary.matches(d: DesiredAssignment?): Boolean =
    d != null && assignedRevision() == d.revision && assignedDigest() == d.dockerImageDigest

private val drainMarkerRegex = Regex("--draining-(\\d+)$")

/**
 * When this container's drain began, recovered from the name-suffix a rename stamped on it when the
 * drain started (see [drainingContainerName]) — or null if it isn't draining. This is the entirety
 * of "adopt a draining container on restart": a freshly restarted agent re-lists containers, finds
 * the same suffix, and resumes waiting on the same deadline. No separate state file to go stale.
 */
fun ContainerSummary.drainStartedAt(): Instant? =
    names
        .firstOrNull()
        ?.trimStart('/')
        ?.let { drainMarkerRegex.find(it) }
        ?.groupValues
        ?.get(1)
        ?.toLongOrNull()
        ?.let(Instant::ofEpochSecond)

/**
 * The name to rename a container to when its drain begins at [now] — [drainStartedAt] parses it
 * back.
 */
internal fun drainingContainerName(container: ContainerSummary, now: Instant): String {
  val base =
      container.names.firstOrNull()?.trimStart('/')?.substringBefore("--draining-")
          ?: container.id.take(12)
  return "$base--draining-${now.epochSecond}"
}

/** A container the agent should start draining this tick: not yet marked, still running. */
data class DrainStart(
    val container: ContainerSummary,
    // The assignment that will take this container's place once it's drained, or null when the
    // profile isn't desired at all any more (unassigned, or a paused/draining-to-empty node).
    val replacement: DesiredAssignment?,
    val deadline: Duration,
)

/** A container already mid-drain, still within its deadline — reported, not acted on. */
data class DrainingContainer(
    val container: ContainerSummary,
    val since: Instant,
    val deadline: Duration,
)

/**
 * What [planReconcile] decided for this tick.
 *
 * The five buckets replace the old (M7-05) two-bucket start/stop plan because a revision or digest
 * bump on a *running* container can no longer be resolved inside one tick: SIGTERM-draining a claim
 * can legitimately take hours (`ProfileRevision.drainDeadline`), and the reconcile loop can't block
 * a tick for hours. So a superseded, still-running container spends one or more ticks moving
 * [toBeginDrain] -> [draining] -> ([toForceStop] if the deadline lapses) -> gone, and only then
 * does its replacement show up in [toStart]. A container that was never running to begin with
 * (already exited/crashed) needs none of that — it's always in [toRemove], immediately.
 */
data class ReconcilePlan(
    val toStart: List<DesiredAssignment>,
    // Not running (exited/crashed) — clean up immediately, no signal needed, nothing to drain.
    val toRemove: List<ContainerSummary>,
    // The subset of [toRemove] that exited while still assigned its current desired digest — i.e.
    // this wasn't cleanup of something already superseded, it's the current target failing on its
    // own. The caller folds these into the crashloop record (see `CrashloopState.kt`); an exit here
    // is what "abnormally" means for the crashloop threshold.
    val crashloopCandidates: List<ContainerSummary>,
    val toBeginDrain: List<DrainStart>,
    val toForceStop: List<ContainerSummary>,
    val draining: List<DrainingContainer>,
)

/**
 * Pure decision function: what changes make the agent-managed containers in [running] match
 * [desired] as of [now]. [running] must already be filtered to agent-managed containers (label
 * [workloadAgentManagedLabel]) — the caller does the Docker `list` call, this function makes no I/O
 * so it's cheap to test exhaustively.
 *
 * **Adoption is a side effect of doing nothing.** A container already running at its assigned
 * profile+revision+digest matches none of the plan's buckets — it's simply left alone, exactly as
 * before the drain contract. A container already draining is adopted the same way: its deadline
 * lives on the container itself ([drainStartedAt]/[ContainerSummary.configuredDrainDeadline]), so a
 * freshly restarted agent computes the exact same bucket for it a running one would.
 *
 * One container per profile is still the invariant: a profile occupied by a running or still-
 * draining container never appears in [ReconcilePlan.toStart], even once its replacement has been
 * pre-pulled — the old one has to actually be gone first, so two revisions of one profile are never
 * up at once.
 */
fun planReconcile(
    desired: List<DesiredAssignment>,
    running: List<ContainerSummary>,
    now: Instant,
    defaultDrainDeadline: Duration = agentReconcileStopGrace,
): ReconcilePlan {
  val desiredByProfile = desired.associateBy { it.profileId }
  val occupiedProfiles = mutableSetOf<String>()

  val toRemove = mutableListOf<ContainerSummary>()
  val crashloopCandidates = mutableListOf<ContainerSummary>()
  val toBeginDrain = mutableListOf<DrainStart>()
  val toForceStop = mutableListOf<ContainerSummary>()
  val draining = mutableListOf<DrainingContainer>()

  for (c in running) {
    val profileId = c.assignedProfileId()
    val d = profileId?.let { desiredByProfile[it] }

    if (!c.running) {
      toRemove += c
      if (c.matches(d)) crashloopCandidates += c
      continue
    }

    if (c.matches(d)) {
      profileId?.let { occupiedProfiles += it }
      continue
    }

    // Running, but superseded (revision/digest moved) or no longer desired at all — still occupies
    // its profile slot (if any) until it's actually gone, whichever bucket it lands in below.
    profileId?.let { occupiedProfiles += it }
    val deadline = c.configuredDrainDeadline(defaultDrainDeadline)
    val startedAt = c.drainStartedAt()
    when {
      startedAt == null -> toBeginDrain += DrainStart(c, d, deadline)
      now.isBefore(startedAt.plus(deadline.toJavaDuration())) ->
          draining += DrainingContainer(c, startedAt, deadline)
      else -> toForceStop += c
    }
  }

  val toStart = desired.filter { it.profileId !in occupiedProfiles }
  return ReconcilePlan(toStart, toRemove, crashloopCandidates, toBeginDrain, toForceStop, draining)
}

private val exitedStatusPattern = Regex("""Exited \((-?\d+)\)""")

/**
 * Whether a dead [ContainerSummary]'s exit looks abnormal (nonzero), parsed off the daemon's own
 * `Status` phrase (`"Exited (1) 2 minutes ago"`) rather than a second `inspect` round-trip. An
 * unparseable status (a `list()` response is inherently a snapshot; the phrasing isn't a stable
 * contract) is conservatively treated as *not* abnormal — a crashloop hold is a big enough hammer
 * that it should only swing on a confident nonzero-exit signal, not a status-string quirk.
 */
fun ContainerSummary.exitedAbnormally(): Boolean =
    exitedStatusPattern.find(status.orEmpty())?.groupValues?.get(1)?.toIntOrNull()?.let { it != 0 }
        ?: false

/** Removes containers that were already dead when listed — nothing to signal, just cleanup. */
suspend fun removeDeadContainers(
    connector: DockerConnector,
    containers: List<ContainerSummary>,
    warn: (String) -> Unit,
) {
  for (container in containers) {
    runCatching { connector.containers.remove(container.id, force = true) }
        .onFailure {
          warn("workload-agent: could not remove ${container.id.take(12)}: ${it.reason()}")
        }
  }
}

/**
 * A container's drain deadline has lapsed while it was still running — escalate to SIGKILL (the
 * drain contract's own escalation, same as a foreground run's Ctrl-C grace) and remove it.
 */
suspend fun forceStopExpiredDrains(
    connector: DockerConnector,
    containers: List<ContainerSummary>,
    warn: (String) -> Unit,
) {
  for (container in containers) {
    runCatching {
          connector.containers.kill(container.id, "SIGKILL")
          connector.containers.remove(container.id, force = true)
        }
        .onFailure {
          warn(
              "workload-agent: drain deadline exceeded, force-stop failed for " +
                  "${container.id.take(12)}: ${it.reason()}"
          )
        }
  }
}

/**
 * Begins draining each [DrainStart]: pre-pulls the replacement's digest first (best-effort — a
 * failed pre-pull only costs the "near-zero swap gap" optimization, `toStart`'s own pull covers
 * correctness), then sends `SIGTERM` (non-blocking —
 * [software.medusa.workload.docker.ContainerApi.kill], not `stop`, since a blocking call can't sit
 * inside a 15s tick for a multi-hour deadline) and stamps the drain-started marker via rename so
 * the deadline survives an agent restart.
 */
suspend fun beginDrains(
    connector: DockerConnector,
    brokerBaseUrl: String,
    auth: BrokerAuth,
    drains: List<DrainStart>,
    now: Instant,
    warn: (String) -> Unit,
) {
  for (drain in drains) {
    drain.replacement?.let { replacement ->
      runCatching {
            val claim = claimWorkload(brokerBaseUrl, auth, replacement.profileId)
            claim.image?.let { pullBrokeredImage(connector, pinnedImageRef(it), claim.accessToken) }
          }
          .onFailure {
            warn("workload-agent: pre-pull for '${replacement.profileId}' failed: ${it.reason()}")
          }
    }
    runCatching {
          connector.containers.kill(drain.container.id, "SIGTERM")
          connector.containers.rename(
              drain.container.id,
              drainingContainerName(drain.container, now),
          )
        }
        .onFailure {
          warn(
              "workload-agent: could not begin drain for ${drain.container.id.take(12)}: " +
                  it.reason()
          )
        }
  }
}

/**
 * Starts a plain (no command-line, no metadata-sidecar) detached container for [assignment]: pulls
 * the pinned digest with the brokered token, creates it labelled agent-managed (including its own
 * drain deadline, see [workloadDrainDeadlineSecondsLabel]), and starts it — returning the new
 * container id, or null (warning) on failure so one bad assignment never blocks the rest of the
 * reconcile pass.
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
        val labels =
            containerLabels(claim, workerId) +
                mapOf(
                    workloadAgentManagedLabel to "true",
                    workloadDrainDeadlineSecondsLabel to
                        (assignment.drainDeadline ?: agentReconcileStopGrace)
                            .inWholeSeconds
                            .toString(),
                )
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

// --- Observability (`admin workers list`) ---

enum class AgentAssignmentState {
  CONVERGED,
  DRAINING,
  CRASHLOOP_HOLD,
  REPLACING,
}

/**
 * One profile's point-in-time status, reported to the broker each tick for `admin workers list`.
 */
data class AgentAssignmentStatus(
    val profileId: String,
    val runningDigest: String?,
    val desiredDigest: String?,
    val state: AgentAssignmentState,
    // When [state] was entered. Exact for DRAINING (backed by the container's own rename marker)
    // and CRASHLOOP_HOLD (the caller passes the earliest recorded failure — see
    // `CrashloopState.kt`); approximated as "now" for REPLACING, which has no durable marker of its
    // own to recover a precise start time from once the pre-replacement container is gone.
    val since: Instant,
    // Only meaningful while [state] is DRAINING.
    val drainDeadline: Instant? = null,
)

/**
 * Summarizes every profile the agent knows about (desired, or with a container still around) into
 * one [AgentAssignmentStatus] each — the source of `admin workers list`'s per-worker digest/drift/
 * state columns. [heldSince] is the crashloop-held profiles, profileId -> earliest recorded failure
 * (see `CrashloopState.kt`); a profile in there always reports CRASHLOOP_HOLD regardless of what's
 * currently running for it.
 */
fun summarizeAssignmentStatuses(
    desired: List<DesiredAssignment>,
    running: List<ContainerSummary>,
    now: Instant,
    heldSince: Map<String, Instant> = emptyMap(),
    defaultDrainDeadline: Duration = agentReconcileStopGrace,
): List<AgentAssignmentStatus> {
  val byProfile =
      running.filter { it.assignedProfileId() != null }.groupBy { it.assignedProfileId()!! }
  val profileIds = desired.map { it.profileId }.toSet() + byProfile.keys
  return profileIds
      .map { profileId ->
        val d = desired.find { it.profileId == profileId }
        val containers = byProfile[profileId].orEmpty()
        val runningContainer = containers.find { it.running }
        val heldAt = heldSince[profileId]
        when {
          heldAt != null ->
              AgentAssignmentStatus(
                  profileId,
                  runningContainer?.assignedDigest(),
                  d?.dockerImageDigest,
                  AgentAssignmentState.CRASHLOOP_HOLD,
                  heldAt,
              )
          runningContainer != null && runningContainer.matches(d) ->
              AgentAssignmentStatus(
                  profileId,
                  runningContainer.assignedDigest(),
                  d?.dockerImageDigest,
                  AgentAssignmentState.CONVERGED,
                  now,
              )
          runningContainer != null && runningContainer.drainStartedAt() != null -> {
            val since = runningContainer.drainStartedAt()!!
            val deadline = runningContainer.configuredDrainDeadline(defaultDrainDeadline)
            AgentAssignmentStatus(
                profileId,
                runningContainer.assignedDigest(),
                d?.dockerImageDigest,
                AgentAssignmentState.DRAINING,
                since,
                since.plus(deadline.toJavaDuration()),
            )
          }
          else ->
              AgentAssignmentStatus(
                  profileId,
                  runningContainer?.assignedDigest(),
                  d?.dockerImageDigest,
                  AgentAssignmentState.REPLACING,
                  now,
              )
        }
      }
      .sortedBy { it.profileId }
}

/**
 * Parses a `ProfileRevision.drainDeadline`-style free-form duration string (e.g. `"90m"`, `"6h"`)
 * into a [Duration] — null (fall back to [agentReconcileStopGrace]) for a blank, missing, or
 * malformed value. The backend stores the string opaquely and never validates it, so the agent has
 * to be lenient here rather than treating a bad value as fatal.
 */
fun parseDrainDeadline(raw: String?): Duration? =
    raw?.takeIf { it.isNotBlank() }?.let { runCatching { Duration.parse(it) }.getOrNull() }
