package software.medusa.workload.runtime

import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RunReporterTest {
  // A long interval so the scheduler never fires on its own — tests drive beatOnce() directly.
  private val neverAutoFires = 3600L

  private fun reporter(
      heartbeat: () -> Unit = {},
      end: (Int?) -> Unit = {},
      warn: (String) -> Unit = {},
  ) = RunReporter("run-1", heartbeat, end, neverAutoFires, warn)

  @Test
  fun `reportEnd forwards the exit code exactly once`() {
    val ends = mutableListOf<Int?>()
    val r = reporter(end = { ends.add(it) })
    try {
      r.reportEnd(7)
      r.reportEnd(0) // idempotent — ignored
      assertEquals(listOf<Int?>(7), ends)
    } finally {
      r.close()
    }
  }

  @Test
  fun `a failing end report is swallowed and warned, never thrown`() {
    val warnings = mutableListOf<String>()
    val r = reporter(end = { throw RuntimeException("broker down") }, warn = { warnings.add(it) })
    try {
      r.reportEnd(0) // must not throw
      assertTrue(warnings.any { it.contains("report the run's end") })
    } finally {
      r.close()
    }
  }

  @Test
  fun `a failing heartbeat is swallowed and warned, never thrown`() {
    val warnings = mutableListOf<String>()
    val r = reporter(heartbeat = { throw RuntimeException("timeout") }, warn = { warnings.add(it) })
    try {
      r.beatOnce() // must not throw
      assertTrue(warnings.any { it.contains("heartbeat failed") })
    } finally {
      r.close()
    }
  }

  @Test
  fun `no heartbeat is sent once the run has ended`() {
    val beats = AtomicInteger(0)
    val r = reporter(heartbeat = { beats.incrementAndGet() })
    try {
      r.reportEnd(0)
      r.beatOnce()
      assertEquals(0, beats.get())
    } finally {
      r.close()
    }
  }

  @Test
  fun `close reports a failure-end when the job ended without one`() {
    val ends = mutableListOf<Int?>()
    val r = reporter(end = { ends.add(it) })
    r.close()
    // A null exit code — a failure with an unknown code (the unexpected-throw / teardown path).
    assertEquals(listOf<Int?>(null), ends)
  }

  @Test
  fun `close after an explicit end does not report again`() {
    val ends = mutableListOf<Int?>()
    val r = reporter(end = { ends.add(it) })
    r.reportEnd(0)
    r.close()
    assertEquals(listOf<Int?>(0), ends)
  }

  @Test
  fun `start returns null and warns when the broker cannot be reached`() {
    val warnings = mutableListOf<String>()
    // Port 1 on loopback: nothing listens, so createRun fails fast — the workload must run
    // untracked.
    val r =
        RunReporter.start(
            brokerBaseUrl = "http://127.0.0.1:1",
            auth = SecretBrokerAuth("wid", "wsecret"),
            profileId = "p",
            revision = 1,
            kind = "run",
            imageDigest = null,
            warn = { warnings.add(it) },
        )
    assertNull(r)
    assertTrue(warnings.any { it.contains("couldn't record the run start") })
  }
}
