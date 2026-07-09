package software.medusa.workload.server

import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerApiPathPrefixTest {

  @Test
  fun `matches the worker token path under the correct prefix`() {
    val match =
        matchWorkerApiPath(
            "/7c9e6679-7425-40de-963d-1a4a2f4bce18/worker/v1/token",
            "7c9e6679-7425-40de-963d-1a4a2f4bce18",
        )
    assertEquals(WorkerApiPathMatch.Matched("/worker/v1/token"), match)
  }

  @Test
  fun `rejects the worker token path under the wrong prefix`() {
    val match = matchWorkerApiPath("/some-other-prefix/worker/v1/token", "correct-prefix")
    assertEquals(WorkerApiPathMatch.PrefixMismatch, match)
  }

  @Test
  fun `rejects the worker token path with no prefix at all`() {
    val match = matchWorkerApiPath("/worker/v1/token", "correct-prefix")
    assertEquals(WorkerApiPathMatch.PrefixMismatch, match)
  }

  @Test
  fun `treats non-worker paths as not applicable regardless of prefix`() {
    assertEquals(WorkerApiPathMatch.NotWorkerPath, matchWorkerApiPath("/health", "correct-prefix"))
    assertEquals(
        WorkerApiPathMatch.NotWorkerPath,
        matchWorkerApiPath("/medusa.workload.v1.WorkloadService/GetCount", "correct-prefix"),
    )
  }

  @Test
  fun `preserves any remainder path past the worker marker`() {
    val match = matchWorkerApiPath("/correct-prefix/worker/v1/token/extra", "correct-prefix")
    assertEquals(WorkerApiPathMatch.Matched("/worker/v1/token/extra"), match)
  }

  @Test
  fun `RejectedWorkerRequestCounter counts increments`() {
    val before = RejectedWorkerRequestCounter.get()
    RejectedWorkerRequestCounter.increment()
    RejectedWorkerRequestCounter.increment()
    assertEquals(before + 2, RejectedWorkerRequestCounter.get())
  }
}
