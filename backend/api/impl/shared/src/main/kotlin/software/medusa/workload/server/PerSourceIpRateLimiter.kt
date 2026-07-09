package software.medusa.workload.server

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A simple fixed-window, per-key rate limiter. In-process only — fine for a single Cloud Run
 * service; this is anti-abuse hygiene (generous limits), not precise traffic shaping.
 */
class PerSourceIpRateLimiter(
    private val maxRequestsPerWindow: Int,
    private val window: Duration,
) {
  private class Window(val startedAt: Instant, val count: AtomicInteger)

  private val windows = ConcurrentHashMap<String, Window>()

  fun tryAcquire(key: String, now: Instant = Instant.now()): Boolean {
    val current =
        windows.compute(key) { _, existing ->
          if (existing == null || Duration.between(existing.startedAt, now) >= window) {
            Window(now, AtomicInteger(0))
          } else {
            existing
          }
        }!!
    return current.count.incrementAndGet() <= maxRequestsPerWindow
  }
}
