package software.medusa.workload.server

import java.time.Duration
import java.time.Instant

/** Default TTL for a `pending` worker before it's treated as rejected on read. */
val pendingWorkerTtl: Duration = Duration.ofHours(24)

/**
 * Applies the pending-worker TTL policy: a `pending` worker older than [pendingWorkerTtl] is
 * surfaced as `REJECTED` to every reader, without writing anything back — lazy expiry, no
 * background job. Garbage-collecting the row itself is a separate, later concern.
 */
internal fun applyPendingExpiry(worker: Worker, now: Instant = Instant.now()): Worker {
  if (worker.status != WorkerStatus.PENDING) return worker
  val expiresAt = worker.createdAt.plus(pendingWorkerTtl)
  return if (now.isBefore(expiresAt)) worker else worker.copy(status = WorkerStatus.REJECTED)
}
