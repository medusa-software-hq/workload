package software.medusa.workload.server

import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

private fun pendingWorker(createdAt: Instant) =
    Worker(
        workerId = WorkerId(UUID.randomUUID()),
        secretHash = SecretHash(ByteArray(0)),
        name = "worker-1",
        hostname = null,
        os = null,
        cliVersion = null,
        status = WorkerStatus.PENDING,
        confirmationCode = "1234",
        createdAt = createdAt,
        approvedAt = null,
        approvedBy = null,
        lastSeenAt = null,
    )

class WorkerPendingExpiryTest {

  @Test
  fun `a pending worker just inside the TTL stays pending`() {
    val now = Instant.parse("2026-01-02T00:00:00Z")
    val worker = pendingWorker(createdAt = now.minus(pendingWorkerTtl).plusSeconds(1))
    assertEquals(WorkerStatus.PENDING, applyPendingExpiry(worker, now).status)
  }

  @Test
  fun `a pending worker exactly at the TTL boundary is expired`() {
    val now = Instant.parse("2026-01-02T00:00:00Z")
    val worker = pendingWorker(createdAt = now.minus(pendingWorkerTtl))
    assertEquals(WorkerStatus.REJECTED, applyPendingExpiry(worker, now).status)
  }

  @Test
  fun `a pending worker past the TTL reads as rejected`() {
    val now = Instant.parse("2026-01-02T00:00:00Z")
    val worker = pendingWorker(createdAt = now.minus(pendingWorkerTtl).minusSeconds(1))
    assertEquals(WorkerStatus.REJECTED, applyPendingExpiry(worker, now).status)
  }

  @Test
  fun `non-pending statuses are never touched, even when old`() {
    val now = Instant.parse("2026-01-02T00:00:00Z")
    val ancient = now.minus(pendingWorkerTtl).minusSeconds(1)
    for (status in listOf(WorkerStatus.ACTIVE, WorkerStatus.REJECTED, WorkerStatus.REVOKED)) {
      val worker = pendingWorker(createdAt = ancient).copy(status = status)
      assertEquals(status, applyPendingExpiry(worker, now).status)
    }
  }
}
