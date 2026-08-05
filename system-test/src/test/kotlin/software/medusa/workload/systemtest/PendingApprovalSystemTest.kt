package software.medusa.workload.systemtest

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.workload.v1.WorkerStatus

/**
 * The pending-approval-then-approve flow: a `require_approval` enrollment token lands the worker
 * `PENDING` instead of `ACTIVE`, `workload worker register` blocks polling for a decision, and an
 * admin's `ApproveWorker` is what lets it through — asserted from both ends at once (the CLI
 * process genuinely blocked, then genuinely unblocked by the approval, not a race).
 */
internal class PendingApprovalSystemTest : SystemTestBase() {

  @Test
  fun `a require_approval token lands pending until an admin approves it`() {
    val token = issueEnrollmentToken("pending-approval", requireApproval = true)
    val configDir = freshConfigDir()
    val workerName = id("worker-pending")

    val registering =
        cliStart(
            "worker",
            "register",
            "--name",
            workerName,
            "--enrollment-token",
            token,
            configDir = configDir,
        )

    val worker = awaitCondition(timeoutSeconds = 30) { target.admin.findWorkerByName(workerName) }
    trackWorker(worker.workerId)
    assertEquals(WorkerStatus.WORKER_STATUS_PENDING, worker.status)

    // The registration process is genuinely still blocked, polling — not already done.
    Thread.sleep(3_000)
    assertTrue(registering.isAlive, "register should still be polling for approval")

    runBlocking { target.admin.approveWorker(worker.workerId) }

    val result = registering.waitFor(timeoutSeconds = 30)
    assertEquals(0, result.exitCode, "register should exit 0 once approved: ${result.combined}")
    assertTrue(
        "Active." in result.stdout,
        "expected the poll to observe the approval: ${result.stdout}",
    )

    val approved = runBlocking { target.admin.findWorkerByName(workerName) }
    assertEquals(WorkerStatus.WORKER_STATUS_ACTIVE, approved?.status)
  }
}
