package software.medusa.workload.versiondrift

import com.google.cloud.iam.credentials.v1.IamCredentialsClient
import com.google.cloud.monitoring.v3.MetricServiceClient
import kotlinx.coroutines.runBlocking
import software.medusa.workload.server.GcpImageDigestResolver
import software.medusa.workload.server.PostgresFleetStore
import software.medusa.workload.server.buildPostgresWorkloadDatabase

private const val databaseUrlEnvVarName = "DATABASE_URL"
private const val gcpProjectIdEnvVarName = "GCP_PROJECT_ID"

/**
 * A Cloud Run job (triggered by Cloud Scheduler, one per environment) implementing the M6/workload
 * automated-rollout epic's Phase 0: a scheduled desired/running/latest-published digest drift check
 * per image-based profile (see VersionDriftChecker). Reads the same Postgres fleet store and does
 * the same registry-manifest lookup the API broker already does for ResolveImage/VerifyProfile — no
 * new worker code, no new self-report. Persistence of the schedule and alerting live in
 * backend/infra/gcp-version-drift-checker.tf and gcp-monitoring.tf.
 */
fun main() = runBlocking {
  val databaseUrl =
      System.getenv(databaseUrlEnvVarName)
          ?: error("$databaseUrlEnvVarName environment variable must be set")
  val gcpProjectId =
      System.getenv(gcpProjectIdEnvVarName)
          ?: error("$gcpProjectIdEnvVarName environment variable must be set")

  val fleetStore = PostgresFleetStore(buildPostgresWorkloadDatabase(databaseUrl))

  IamCredentialsClient.create().use { iamCredentialsClient ->
    MetricServiceClient.create().use { metricServiceClient ->
      val checker =
          VersionDriftChecker(
              fleetStore = fleetStore,
              imageDigestResolver = GcpImageDigestResolver(iamCredentialsClient),
              metricWriter = CloudMonitoringDriftMetricWriter(metricServiceClient, gcpProjectId),
          )

      val results = checker.run()
      val runningDrift = results.count { it.runningMismatch }
      val publishedDrift = results.count { it.publishedMismatch }
      println(
          "Checked ${results.size} image-based profile(s): " +
              "$runningDrift running-digest mismatch(es), $publishedDrift published-digest mismatch(es)."
      )
    }
  }
}
