package software.medusa.workload.runtime

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordCrashloopFailureTest {
  @Test
  fun `stays not-holding under the threshold`() {
    var records = emptyList<CrashloopRecord>()
    repeat(crashloopThreshold) { i ->
      records = recordCrashloopFailure(records, "a", "sha256:1", nowEpochSeconds = 1000L + i)
    }
    assertFalse(isCrashloopHeld(records, "a", "sha256:1"))
  }

  @Test
  fun `holds once failures exceed the threshold within the window`() {
    var records = emptyList<CrashloopRecord>()
    repeat(crashloopThreshold + 1) { i ->
      records = recordCrashloopFailure(records, "a", "sha256:1", nowEpochSeconds = 1000L + i)
    }
    assertTrue(isCrashloopHeld(records, "a", "sha256:1"))
  }

  @Test
  fun `failures outside the window are pruned and don't count`() {
    var records = emptyList<CrashloopRecord>()
    // A burst long enough to hold, all outside the window by the time the last one lands.
    repeat(crashloopThreshold + 1) { i ->
      records = recordCrashloopFailure(records, "a", "sha256:1", nowEpochSeconds = 0L + i)
    }
    // One more failure far in the future — every earlier timestamp is now outside the window.
    val farFuture = crashloopWindow.inWholeSeconds * 10
    records = recordCrashloopFailure(records, "a", "sha256:1", nowEpochSeconds = farFuture)
    // Once held, the record stays held even after the burst that caused it ages out — hold is a
    // one-way door until the digest changes.
    assertTrue(isCrashloopHeld(records, "a", "sha256:1"))
  }

  @Test
  fun `different digests of the same profile are tracked independently`() {
    var records = emptyList<CrashloopRecord>()
    repeat(crashloopThreshold + 1) { i ->
      records = recordCrashloopFailure(records, "a", "sha256:old", nowEpochSeconds = 1000L + i)
    }
    assertTrue(isCrashloopHeld(records, "a", "sha256:old"))
    assertFalse(isCrashloopHeld(records, "a", "sha256:new"))
  }
}

class PruneCrashloopRecordsTest {
  @Test
  fun `drops records whose profile is no longer desired at all`() {
    val records = listOf(CrashloopRecord("a", "sha256:1", listOf(1L), holding = true))
    val pruned = pruneCrashloopRecords(records, desiredDigestsByProfile = emptyMap())
    assertTrue(pruned.isEmpty())
  }

  @Test
  fun `a digest bump clears the hold by dropping the old record`() {
    val records = listOf(CrashloopRecord("a", "sha256:old", listOf(1L), holding = true))
    val pruned =
        pruneCrashloopRecords(records, desiredDigestsByProfile = mapOf("a" to "sha256:new"))
    assertTrue(pruned.isEmpty())
    assertFalse(isCrashloopHeld(pruned, "a", "sha256:new"))
  }

  @Test
  fun `keeps a record still matching the current desired digest`() {
    val records = listOf(CrashloopRecord("a", "sha256:1", listOf(1L), holding = false))
    val pruned = pruneCrashloopRecords(records, desiredDigestsByProfile = mapOf("a" to "sha256:1"))
    assertEquals(records, pruned)
  }
}

class CrashloopPersistenceTest {
  @Test
  fun `round-trips through save and load`() {
    val dir = createTempDirectory("crashloop-state-test")
    val records = listOf(CrashloopRecord("a", "sha256:1", listOf(1L, 2L), holding = true))
    saveCrashloopRecords(dir, records)
    assertEquals(records, loadCrashloopRecords(dir))
  }

  @Test
  fun `loading from a missing file returns empty, never throws`() {
    val dir = createTempDirectory("crashloop-state-test-missing")
    assertEquals(emptyList(), loadCrashloopRecords(dir))
  }
}
