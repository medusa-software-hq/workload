package software.medusa.workload.server

import java.util.concurrent.atomic.AtomicLong

/**
 * Counts worker-plane requests dropped as bot/scanner noise — a malformed or absent credential on a
 * v2 endpoint (see the parse-and-drop decorators in [buildServer]) — so it can be observed via
 * `/internal/metrics` without writing one audit-log line per bot hit.
 */
internal object RejectedWorkerRequestCounter {
  private val count = AtomicLong(0)

  fun increment() {
    count.incrementAndGet()
  }

  fun get(): Long = count.get()
}
