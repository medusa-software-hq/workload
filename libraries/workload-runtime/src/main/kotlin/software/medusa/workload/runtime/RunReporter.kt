package software.medusa.workload.runtime

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Reports a run's presence to the broker (M6-B1): it heartbeats on a daemon thread while the
 * workload runs and reports the terminal exit code when it finishes — including the Ctrl-C path,
 * via a shutdown hook.
 *
 * **Observability must never reduce availability.** Every broker call here is best-effort: a failed
 * heartbeat or end report warns and is otherwise swallowed — it never aborts, kills, or delays the
 * job. If even the initial run-create fails, [start] returns null and the workload runs untracked.
 *
 * Construct via [start]; drive it with [reportEnd] on the normal exit path and [close] in a
 * `finally` (which reports a failure-end if the job threw before reporting one, and unregisters the
 * hook). Both [reportEnd] and [close] are idempotent, so the normal path, the `finally`, and the
 * shutdown hook can all fire without double-reporting.
 */
class RunReporter
internal constructor(
    val runId: String,
    private val heartbeat: () -> Unit,
    private val end: (Int?) -> Unit,
    intervalSeconds: Long,
    private val warn: (String) -> Unit,
) : AutoCloseable {
  private val ended = AtomicBoolean(false)
  private val scheduler: ScheduledExecutorService =
      Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "workload-heartbeat").apply { isDaemon = true }
      }
  // Reports a failure-end if the JVM is going down (Ctrl-C/SIGTERM) before an explicit end — the
  // exit code isn't knowable here, so it's recorded as a failure with an unknown code.
  private val shutdownHook = Thread { reportEnd(null) }

  init {
    scheduler.scheduleWithFixedDelay(::beatOnce, intervalSeconds, intervalSeconds, TimeUnit.SECONDS)
    Runtime.getRuntime().addShutdownHook(shutdownHook)
  }

  internal fun beatOnce() {
    if (ended.get()) return
    runCatching { heartbeat() }
        .onFailure {
          warn("workload: run heartbeat failed (${it.message}); the job keeps running.")
        }
  }

  /** Reports the run's end exactly once. Safe from the normal path and from the shutdown hook. */
  fun reportEnd(exitCode: Int?) {
    if (!ended.compareAndSet(false, true)) return
    scheduler.shutdownNow()
    runCatching { end(exitCode) }
        .onFailure { warn("workload: couldn't report the run's end (${it.message}).") }
  }

  /**
   * Idempotent teardown: reports a failure-end if the job exited without one (an unexpected throw),
   * then unregisters the shutdown hook. A no-op end when [reportEnd] already ran.
   */
  override fun close() {
    reportEnd(null)
    // removeShutdownHook throws IllegalStateException once shutdown is underway — expected on the
    // Ctrl-C path, where the hook is firing anyway; swallow it.
    runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
  }

  companion object {
    /**
     * Records the run's start and returns a reporter armed with a heartbeat loop, or null if the
     * broker couldn't be told (the workload then runs without presence tracking — never blocked).
     */
    fun start(
        brokerBaseUrl: String,
        auth: BrokerAuth,
        profileId: String?,
        revision: Int?,
        kind: String,
        imageDigest: String?,
        warn: (String) -> Unit,
    ): RunReporter? {
      val created =
          try {
            createRun(brokerBaseUrl, auth, profileId, revision, kind, imageDigest)
          } catch (e: Exception) {
            warn(
                "workload: couldn't record the run start (${e.message}); " +
                    "continuing without run tracking."
            )
            return null
          }
      return RunReporter(
          runId = created.runId,
          heartbeat = { heartbeatRun(brokerBaseUrl, auth, created.runId) },
          end = { code -> endRun(brokerBaseUrl, auth, created.runId, code) },
          intervalSeconds = created.heartbeatIntervalSeconds,
          warn = warn,
      )
    }
  }
}
