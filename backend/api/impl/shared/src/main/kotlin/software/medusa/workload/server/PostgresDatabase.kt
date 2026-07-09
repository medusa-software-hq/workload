package software.medusa.workload.server

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import javax.sql.DataSource
import org.flywaydb.core.Flyway
import software.medusa.workload.db.WorkloadDatabase

private const val maxPoolSize = 5

/**
 * Builds a [WorkloadDatabase] backed by the database at [jdbcUrl] (a full JDBC URL including
 * credentials and `sslmode=require`), running pending Flyway migrations before returning.
 *
 * Flyway owns the runtime schema; SQLDelight only provides type-safe queries, so we do not call
 * [WorkloadDatabase.Schema] create/migrate here.
 *
 * All Postgres-backed stores ([PostgresWorkloadStore], [PostgresFleetStore]) share the tables in
 * one physical database, so call this once and construct both stores from the same instance rather
 * than each opening its own connection pool.
 */
fun buildPostgresWorkloadDatabase(jdbcUrl: String): WorkloadDatabase {
  val dataSource: DataSource =
      HikariDataSource(
          HikariConfig().apply {
            this.jdbcUrl = jdbcUrl
            // Register the driver explicitly instead of relying on DriverManager's ServiceLoader
            // auto-registration, which is unreliable in the packaged Cloud Run image (it fails
            // with "No suitable driver" even though pgjdbc is on the classpath).
            driverClassName = "org.postgresql.Driver"
            maximumPoolSize = maxPoolSize
          }
      )

  Flyway.configure().dataSource(dataSource).load().migrate()

  return WorkloadDatabase(dataSource.asJdbcDriver())
}
