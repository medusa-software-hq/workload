package software.medusa.workload.runtime

import java.nio.file.FileStore
import java.nio.file.Files
import java.nio.file.Path
import software.medusa.workload.docker.DockerConnector

/**
 * Widens [garbageCollectAfterRun]'s per-run GC into disk-pressure-triggered cleanup (M7-05): the
 * `workload-agent` daemon calls this on a timer (not just after a pull), so a long-lived node whose
 * disk fills from *any* source — not only its own superseded revisions — reclaims space before it
 * runs out. Same kubelet-style high/low watermark shape: cross [highWatermarkPercent] used and it
 * sweeps every recorded repository down to nothing (keepDigests empty, exactly like `workload
 * prune`), stopping as soon as usage drops back under [lowWatermarkPercent] or there's nothing left
 * to collect — whichever comes first. Below [highWatermarkPercent], a no-op.
 */

/**
 * Used-space percentage crossing this triggers a sweep. Mirrors kubelet's default image-gc-high.
 */
const val defaultDiskPressureHighWatermarkPercent = 85.0

/** A sweep stops once used space drops back under this. Mirrors kubelet's default image-gc-low. */
const val defaultDiskPressureLowWatermarkPercent = 80.0

/**
 * Percentage of [store]'s capacity currently used, or null if it can't be read (e.g. missing path).
 */
fun usedSpacePercent(store: FileStore): Double? {
  val total = store.totalSpace
  if (total <= 0) return null
  val usable = store.usableSpace
  return (total - usable).toDouble() / total.toDouble() * 100.0
}

/**
 * Sweeps every repository [workload prune][PruneCommand] would (everything recorded in
 * [loadManagedRepos] for [configDir], keeping nothing, respecting live containers) while
 * [dockerRoot] stays over [highWatermarkPercent] used — checked once, then re-checked after each
 * repository so a sweep stops the moment it's no longer needed rather than always running to
 * completion. Entirely best-effort, like the rest of this file's GC: every failure is a [warn],
 * never thrown. Returns whether the high watermark was crossed at all (so the caller can log a
 * clean "nothing to do").
 */
suspend fun collectUnderDiskPressure(
    connector: DockerConnector,
    configDir: Path,
    dockerRoot: Path,
    highWatermarkPercent: Double = defaultDiskPressureHighWatermarkPercent,
    lowWatermarkPercent: Double = defaultDiskPressureLowWatermarkPercent,
    warn: (String) -> Unit,
): Boolean {
  val store =
      runCatching { Files.getFileStore(dockerRoot) }
          .getOrElse {
            warn(
                "workload-agent: disk-pressure check skipped, can't stat $dockerRoot (${it.reason()})"
            )
            return false
          }
  val startingUsage =
      usedSpacePercent(store)
          ?: return false.also {
            warn("workload-agent: disk-pressure check skipped, $dockerRoot reports zero capacity")
          }
  if (startingUsage < highWatermarkPercent) return false

  warn(
      "workload-agent: disk usage ${"%.1f".format(startingUsage)}% over the " +
          "$highWatermarkPercent% watermark; sweeping."
  )
  runCatching { connector.containers.prune(labelKeys = listOf(workloadProfileLabel)) }
      .onFailure { warn("workload-agent: could not reap exited containers (${it.reason()})") }

  val repos = loadManagedRepos(configDir)
  val inUse = imageRefsInUse(connector)
  for (repository in repos) {
    val usage = usedSpacePercent(store)
    if (usage == null || usage < lowWatermarkPercent) break
    sweepRepository(
        connector = connector,
        repository = repository,
        keepDigests = emptySet(),
        inUse = inUse,
        warn = warn,
    )
  }
  return true
}
