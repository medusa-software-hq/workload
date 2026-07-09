package software.medusa.workload.server

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerSourceIpRateLimiterTest {

  @Test
  fun `allows up to the configured limit within a window`() {
    val limiter = PerSourceIpRateLimiter(maxRequestsPerWindow = 3, window = Duration.ofMinutes(1))
    val now = Instant.parse("2026-01-01T00:00:00Z")

    assertTrue(limiter.tryAcquire("1.2.3.4", now))
    assertTrue(limiter.tryAcquire("1.2.3.4", now))
    assertTrue(limiter.tryAcquire("1.2.3.4", now))
    assertFalse(limiter.tryAcquire("1.2.3.4", now))
  }

  @Test
  fun `tracks each key independently`() {
    val limiter = PerSourceIpRateLimiter(maxRequestsPerWindow = 1, window = Duration.ofMinutes(1))
    val now = Instant.parse("2026-01-01T00:00:00Z")

    assertTrue(limiter.tryAcquire("1.2.3.4", now))
    assertTrue(limiter.tryAcquire("5.6.7.8", now))
    assertFalse(limiter.tryAcquire("1.2.3.4", now))
  }

  @Test
  fun `resets once the window elapses`() {
    val limiter = PerSourceIpRateLimiter(maxRequestsPerWindow = 1, window = Duration.ofMinutes(1))
    val now = Instant.parse("2026-01-01T00:00:00Z")

    assertTrue(limiter.tryAcquire("1.2.3.4", now))
    assertFalse(limiter.tryAcquire("1.2.3.4", now.plusSeconds(30)))
    assertTrue(limiter.tryAcquire("1.2.3.4", now.plusSeconds(61)))
  }
}
