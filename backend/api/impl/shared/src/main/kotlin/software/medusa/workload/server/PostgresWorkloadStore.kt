package software.medusa.workload.server

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import software.medusa.workload.db.WorkloadDatabase

private const val maxPoolSize = 5

class PostgresWorkloadStore(
    private val database: WorkloadDatabase,
) : WorkloadStore {
  companion object {
    /**
     * Builds a store backed by the database at [jdbcUrl] (a full JDBC URL including credentials and
     * `sslmode=require`), running pending Flyway migrations before returning.
     *
     * Flyway owns the runtime schema; SQLDelight only provides type-safe queries, so we do not call
     * [WorkloadDatabase.Schema] create/migrate here.
     */
    fun build(jdbcUrl: String): PostgresWorkloadStore {
      val dataSource: DataSource =
          HikariDataSource(
              HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                // Register the driver explicitly instead of relying on
                // DriverManager's ServiceLoader auto-registration, which is
                // unreliable in the packaged Cloud Run image (it fails with
                // "No suitable driver" even though pgjdbc is on the classpath).
                driverClassName = "org.postgresql.Driver"
                maximumPoolSize = maxPoolSize
              }
          )

      Flyway.configure().dataSource(dataSource).load().migrate()

      return PostgresWorkloadStore(WorkloadDatabase(dataSource.asJdbcDriver()))
    }
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
