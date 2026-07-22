package software.medusa.workload.server

import java.time.Duration
import java.time.Instant

/**
 * The server-controlled heartbeat cadence handed to the CLI in the create-run response. One knob:
 * change it here and every client picks up the new interval on its next run.
 */
val heartbeatInterval: Duration = Duration.ofSeconds(30)

/**
 * How stale a still-`RUNNING` run's last heartbeat may get before readers treat it as
 * [RunState.LOST]: 3× the interval, so a single dropped heartbeat never trips it — same slack GCE
 * gives a missed lease renewal.
 */
val lostAfter: Duration = heartbeatInterval.multipliedBy(3)

/**
 * Derives a run's *effective* state from what's stored — the sole place `lost` comes into being. A
 * run that has ended keeps its stored terminal state; a still-running one whose last heartbeat is
 * older than [lostAfter] surfaces as [RunState.LOST], without writing anything back (lazy
 * derivation, no background job). A later heartbeat moves [Run.lastHeartbeatAt] forward and the run
 * silently un-loses itself.
 */
internal fun deriveRunState(
    storedState: RunState,
    lastHeartbeatAt: Instant,
    endedAt: Instant?,
    now: Instant = Instant.now(),
): RunState {
  if (endedAt != null || storedState != RunState.RUNNING) return storedState
  val lostAt = lastHeartbeatAt.plus(lostAfter)
  return if (now.isBefore(lostAt)) RunState.RUNNING else RunState.LOST
}

/** Applies [deriveRunState] to a [Run] read straight from storage. */
internal fun applyRunStateDerivation(run: Run, now: Instant = Instant.now()): Run {
  val effective = deriveRunState(run.state, run.lastHeartbeatAt, run.endedAt, now)
  return if (effective == run.state) run else run.copy(state = effective)
}

/**
 * The terminal state a reported [exitCode] implies: 0 is success, anything else (or none) failure.
 */
internal fun terminalStateFor(exitCode: Int?): RunState =
    if (exitCode == 0) RunState.SUCCEEDED else RunState.FAILED
