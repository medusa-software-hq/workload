package software.medusa.workload.server

import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import software.medusa.workload.db.WorkloadDatabase

private const val testDatabaseUrlEnvVarName = "TEST_DATABASE_URL"

/**
 * Runs [FleetStoreContractTest] against a real Postgres, skipped unless [testDatabaseUrlEnvVarName]
 * points at one (e.g. `postgresql://user:pass@localhost:5432/db?sslmode=disable`) — there's no
 * Postgres available in this repo's CI today, matching [PostgresWorkloadStore]'s existing (lack of)
 * test coverage. Point it at a local/throwaway database to run this locally.
 */
class PostgresFleetStoreTest : FleetStoreContractTest() {
  @BeforeEach
  fun requireTestDatabase() {
    assumeTrue(
        System.getenv(testDatabaseUrlEnvVarName) != null,
        "$testDatabaseUrlEnvVarName not set",
    )
  }

  override fun createStore(): FleetStore {
    val jdbcUrl = System.getenv(testDatabaseUrlEnvVarName)
    val dataSource =
        HikariDataSource(
            HikariConfig().apply {
              this.jdbcUrl = jdbcUrl
              driverClassName = "org.postgresql.Driver"
              maximumPoolSize = 2
            }
        )
    Flyway.configure().dataSource(dataSource).cleanDisabled(false).load().apply {
      clean()
      migrate()
    }
    return PostgresFleetStore(WorkloadDatabase(dataSource.asJdbcDriver()))
  }
}
