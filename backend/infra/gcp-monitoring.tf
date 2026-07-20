# Cloud Monitoring for the M4-A6 front-door token refresher (see gcp-front-door-refresher.tf).
#
# The refresher is the single point of failure for the whole front door: it pushes a fresh invoker
# ID token to the Cloudflare Worker every 15 min, and that token lives ~60 min. If the loop silently
# dies, the entire API goes dark ~45 min later with *no other signal*. Two alert policies cover the
# two ways it can die:
#
#   1. Failure  — an execution ran but returned non-zero (bad token push, CF API error, etc.).
#   2. Staleness — no *successful* execution completed in > 20 min. This is a missing-data
#      (absent_for) condition, so a job that is silently not running at all — scheduler paused,
#      trigger deleted, quota wall — fires it too, which a pure "execution failed" metric never would.
#
# Both notify the shared team email (infra/common). Cadence is 15 min against a 60-min token, so the
# 20-min staleness window trips after a single fully-missed run while still leaving ~40 min of runway
# before the token actually expires — alert first, outage later.
#
# NB: monitoring.googleapis.com must be enabled on the project for the resources below to apply. Like
# this project's other Google APIs (run, secretmanager, …) it is enabled out-of-band, NOT via a
# google_project_service resource here — the CI/Terraform identity lacks serviceusage.services.enable,
# so managing it in-config just 403s. It was enabled by hand once (`gcloud services enable
# monitoring.googleapis.com --project <id>`); see backend/infra/README.md.

resource "google_monitoring_notification_channel" "team_email" {
  project      = var.gcp_project_id
  display_name = "Team email"
  type         = "email"

  labels = {
    email_address = module.common.alert_notification_email
  }
}

resource "google_monitoring_alert_policy" "front_door_refresher_failed" {
  project      = var.gcp_project_id
  display_name = "Front-door refresher: execution failed"
  combiner     = "OR"
  severity     = "CRITICAL"

  conditions {
    display_name = "front-door-refresher completed with result=failed"

    condition_threshold {
      filter = join(" AND ", [
        "resource.type = \"cloud_run_job\"",
        "resource.labels.job_name = \"${google_cloud_run_v2_job.front_door_refresher.name}\"",
        "metric.type = \"run.googleapis.com/job/completed_execution_count\"",
        "metric.labels.result = \"failed\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = "0s"

      # completed_execution_count is a DELTA metric emitted only when an execution finishes; summing
      # it over a 5-min window turns any failed run in that window into a >0 sample that trips at once.
      aggregations {
        alignment_period   = "300s"
        per_series_aligner = "ALIGN_DELTA"
      }

      trigger {
        count = 1
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.team_email.id]

  alert_strategy {
    # Auto-close once no further failures land, so a one-off transient failure clears itself.
    auto_close = "1800s"
  }

  documentation {
    content = join("\n", [
      "The front-door token refresher job (`front-door-refresher`) had an execution finish with a",
      "**failed** result. The invoker ID token pushed to the Cloudflare Worker may be stale; the API",
      "front door goes dark once the last good token expires (~60 min after it was minted).",
      "",
      "Check the job's execution logs:",
      "  gcloud run jobs executions list --job=front-door-refresher --region=${module.common.gcp_primary_location} --project=${var.gcp_project_id}",
      "",
      "Common causes: the Cloudflare API token (Secret Manager `cloudflare-front-door-refresh-token`)",
      "was rotated/revoked, or the CF API rejected the secret update.",
    ])
    mime_type = "text/markdown"
  }
}

resource "google_monitoring_alert_policy" "front_door_refresher_stale" {
  project      = var.gcp_project_id
  display_name = "Front-door refresher: no successful run in 20m"
  combiner     = "OR"
  severity     = "CRITICAL"

  conditions {
    display_name = "no succeeded execution for front-door-refresher in 20m"

    # MQL, not a threshold: absent_for gives true-missing-data semantics. A threshold on the
    # succeeded-count can only fire while data flows; if the job stops emitting entirely (paused
    # scheduler, deleted trigger) there is nothing to threshold. absent_for fires precisely because
    # the series went missing — the failure mode we most need to catch.
    condition_monitoring_query_language {
      # align delta(5m) | every 5m gives the query the explicit alignment window the Monitoring API
      # requires (a bare group_by is rejected). A succeeded execution lands a point in its 5m window;
      # with a run every 15 min the gap between points stays well under absent_for's 20m, so absent_for
      # fires only when executions actually stop.
      query    = <<-EOT
        fetch cloud_run_job
        | metric 'run.googleapis.com/job/completed_execution_count'
        | filter (metric.result == 'succeeded' && resource.job_name == '${google_cloud_run_v2_job.front_door_refresher.name}')
        | align delta(5m)
        | every 5m
        | group_by [], [executions: row_count()]
        | absent_for 20m
      EOT
      duration = "0s"

      trigger {
        count = 1
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.team_email.id]

  documentation {
    content = join("\n", [
      "The front-door token refresher job (`front-door-refresher`) has had **no successful execution",
      "in over 20 minutes**. It runs every 15 min, so a single fully-missed run trips this — the job",
      "is failing, the Cloud Scheduler trigger is paused/deleted, or the job itself is gone.",
      "",
      "The invoker ID token behind the API front door expires ~60 min after its last successful push,",
      "so the whole API goes dark shortly after this fires if it is not resolved.",
      "",
      "Check the scheduler trigger and the job:",
      "  gcloud scheduler jobs describe front-door-refresher --location=${module.common.gcp_primary_location} --project=${var.gcp_project_id}",
      "  gcloud run jobs executions list --job=front-door-refresher --region=${module.common.gcp_primary_location} --project=${var.gcp_project_id}",
    ])
    mime_type = "text/markdown"
  }
}
