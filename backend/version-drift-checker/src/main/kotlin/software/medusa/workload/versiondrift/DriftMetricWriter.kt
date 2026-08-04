package software.medusa.workload.versiondrift

import com.google.api.Metric
import com.google.api.MonitoredResource
import com.google.cloud.monitoring.v3.MetricServiceClient
import com.google.monitoring.v3.CreateTimeSeriesRequest
import com.google.monitoring.v3.Point
import com.google.monitoring.v3.ProjectName
import com.google.monitoring.v3.TimeInterval
import com.google.monitoring.v3.TimeSeries
import com.google.monitoring.v3.TypedValue
import com.google.protobuf.Timestamp
import java.time.Instant
import org.slf4j.LoggerFactory

/** Custom Cloud Monitoring metric types the alert policies in gcp-monitoring.tf key off of. */
const val desiredVsRunningMetricType =
    "custom.googleapis.com/workload/version_drift/desired_vs_running"
const val publishedVsDesiredMetricType =
    "custom.googleapis.com/workload/version_drift/published_vs_desired"

/** Writes one [ProfileDriftResult] as a pair of 0/1 gauge points, one per comparison. */
interface DriftMetricWriter {
  fun write(result: ProfileDriftResult, now: Instant)
}

/**
 * Writes both gauges as Cloud Monitoring custom metrics, labeled by `profile_id`. A gauge is
 * written on *every* check — including the non-mismatched (0) case — so the time series stays
 * continuous: the alert policies key off `duration` (the condition must hold for N hours straight),
 * which only behaves correctly against an unbroken series. A profile that stops drifting reports 0
 * immediately, which is what lets the corresponding alert auto-close.
 */
class CloudMonitoringDriftMetricWriter(
    private val client: MetricServiceClient,
    private val gcpProjectId: String,
) : DriftMetricWriter {
  override fun write(result: ProfileDriftResult, now: Instant) {
    val resource =
        MonitoredResource.newBuilder()
            .setType("global")
            .putLabels("project_id", gcpProjectId)
            .build()

    val timeSeries =
        listOf(
            gauge(
                desiredVsRunningMetricType,
                result.profileId,
                result.runningMismatch,
                now,
                resource,
            ),
            gauge(
                publishedVsDesiredMetricType,
                result.profileId,
                result.publishedMismatch,
                now,
                resource,
            ),
        )

    val request =
        CreateTimeSeriesRequest.newBuilder()
            .setName(ProjectName.of(gcpProjectId).toString())
            .addAllTimeSeries(timeSeries)
            .build()
    client.createTimeSeries(request)
  }

  private fun gauge(
      metricType: String,
      profileId: String,
      mismatch: Boolean,
      now: Instant,
      resource: MonitoredResource,
  ): TimeSeries {
    val interval =
        TimeInterval.newBuilder()
            .setEndTime(Timestamp.newBuilder().setSeconds(now.epochSecond).setNanos(now.nano))
            .build()
    val point =
        Point.newBuilder()
            .setInterval(interval)
            .setValue(TypedValue.newBuilder().setInt64Value(if (mismatch) 1 else 0))
            .build()
    val metric = Metric.newBuilder().setType(metricType).putLabels("profile_id", profileId).build()
    return TimeSeries.newBuilder().setMetric(metric).setResource(resource).addPoints(point).build()
  }
}

/** Logs the verdict instead of writing to Cloud Monitoring — local runs, dry runs. */
object LoggingDriftMetricWriter : DriftMetricWriter {
  private val logger = LoggerFactory.getLogger(LoggingDriftMetricWriter::class.java)

  override fun write(result: ProfileDriftResult, now: Instant) {
    logger.info(
        "profile={} desired={} runningMismatch={} publishedMismatch={}",
        result.profileId,
        result.desiredDigest,
        result.runningMismatch,
        result.publishedMismatch,
    )
  }
}
