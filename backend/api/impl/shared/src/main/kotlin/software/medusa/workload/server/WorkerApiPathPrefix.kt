package software.medusa.workload.server

import java.util.concurrent.atomic.AtomicLong

private const val workerPathMarker = "/worker/v1/"

/** Result of checking a request path against the deployment's worker API path prefix. */
internal sealed interface WorkerApiPathMatch {
  /** The path is `/<expectedPrefix>/worker/v1/...`; [remainder] starts with `/worker/v1/`. */
  data class Matched(val remainder: String) : WorkerApiPathMatch

  /** The path looks like a worker-plane request, but the prefix segment is wrong or missing. */
  data object PrefixMismatch : WorkerApiPathMatch

  /** The path isn't a worker-plane request at all (e.g. the gRPC/admin plane, `/health`). */
  data object NotWorkerPath : WorkerApiPathMatch
}

/**
 * Classifies [path] as a worker-plane request under [expectedPrefix], a worker-plane request under
 * the wrong (or missing) prefix, or an unrelated path.
 *
 * Only the presence of `/worker/v1/` in the path marks it as worker-plane traffic; everything else
 * (gRPC, `/health`) is untouched by the prefix requirement.
 */
internal fun matchWorkerApiPath(path: String, expectedPrefix: String): WorkerApiPathMatch {
  val markerIndex = path.indexOf(workerPathMarker)
  if (markerIndex < 0) return WorkerApiPathMatch.NotWorkerPath

  val prefixSegment = path.substring(0, markerIndex)
  return if (prefixSegment == "/$expectedPrefix") {
    WorkerApiPathMatch.Matched(path.substring(markerIndex))
  } else {
    WorkerApiPathMatch.PrefixMismatch
  }
}

/**
 * Counts worker-plane requests rejected for a wrong/missing path prefix — internet background
 * noise, not real traffic — so it can be observed without writing one audit-log line per bot hit.
 */
internal object RejectedWorkerRequestCounter {
  private val count = AtomicLong(0)

  fun increment() {
    count.incrementAndGet()
  }

  fun get(): Long = count.get()
}
