package software.medusa.workload.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import software.medusa.workload.db.WorkloadDatabase

class PostgresWorkloadStore(
    private val database: WorkloadDatabase,
) : WorkloadStore {
  companion object {
    /** See [buildPostgresWorkloadDatabase]. */
    fun build(jdbcUrl: String): PostgresWorkloadStore =
        PostgresWorkloadStore(buildPostgresWorkloadDatabase(jdbcUrl))
  }

  override suspend fun getCount(counterId: CounterId): Int =
      withContext(Dispatchers.IO) {
        database.workloadQueries.selectCount(counterId.id).executeAsOneOrNull() ?: 0
      }

  override suspend fun incrementAndGetCount(counterId: CounterId): Int = adjust(counterId, +1)

  override suspend fun decrementAndGetCount(counterId: CounterId): Int = adjust(counterId, -1)

  private suspend fun adjust(counterId: CounterId, delta: Int): Int =
      withContext(Dispatchers.IO) {
        database.workloadQueries.adjustCount(counterId.id, delta).executeAsOne()
      }
}
