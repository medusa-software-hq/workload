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

# Version drift/staleness alerts (automated-rollout epic Phase 0, workload#126). The
# version-drift-checker job (gcp-version-drift-checker.tf) writes two 0/1 gauges per image-based
# profile on every run: desired_vs_running (a live worker's self-reported digest doesn't match what's
# pinned) and published_vs_desired (the image tag now resolves to a digest nobody pinned yet). Both
# alert policies below use condition_threshold's `duration` to require the mismatch hold continuously
# — a one-off blip during a normal rollout must not page anyone, only a mismatch that outlives the
# window it should have self-healed within.
locals {
  # How long a live run may run an old digest before "the worker never converged" beats "still
  # draining a job that started before the rollout".
  version_drift_running_window = "28800s" # 8h
  # How long a profile's image tag may point past the pinned digest before "someone published but
  # nobody rolled" — the exact gap the stale-worker incident exposed.
  version_drift_published_window = "7200s" # 2h

  # Must match desiredVsRunningMetricType / publishedVsDesiredMetricType in
  # backend/version-drift-checker/.../DriftMetricWriter.kt.
  version_drift_desired_vs_running_metric_type   = "custom.googleapis.com/workload/version_drift/desired_vs_running"
  version_drift_published_vs_desired_metric_type = "custom.googleapis.com/workload/version_drift/published_vs_desired"
}

# The alert policies below key off these custom metric types, but a custom metric only comes into
# existence once something writes a point to it — here, the version-drift checker's first run, which
# happens *after* this apply. Creating an alert policy that references a not-yet-existent metric fails
# with a 404 ("Cannot find metric(s) that match type ..."). Declaring the descriptors up front — and
# making the policies depend on them — guarantees the metric type exists at apply time. Shape mirrors
# DriftMetricWriter.kt: a 0/1 INT64 GAUGE labeled by profile_id.
resource "google_monitoring_metric_descriptor" "version_drift_desired_vs_running" {
  project      = var.gcp_project_id
  type         = local.version_drift_desired_vs_running_metric_type
  metric_kind  = "GAUGE"
  value_type   = "INT64"
  display_name = "Version drift: desired vs running"
  description  = "1 when a live run reports an image digest other than its profile's pinned digest, else 0."

  labels {
    key         = "profile_id"
    value_type  = "STRING"
    description = "The Workload profile the drift was observed on."
  }
}

resource "google_monitoring_metric_descriptor" "version_drift_published_vs_desired" {
  project      = var.gcp_project_id
  type         = local.version_drift_published_vs_desired_metric_type
  metric_kind  = "GAUGE"
  value_type   = "INT64"
  display_name = "Version drift: published vs desired"
  description  = "1 when a newer image is published than the profile's pinned (desired) digest, else 0."

  labels {
    key         = "profile_id"
    value_type  = "STRING"
    description = "The Workload profile the drift was observed on."
  }
}

resource "google_monitoring_alert_policy" "version_drift_running_stale" {
  depends_on = [google_monitoring_metric_descriptor.version_drift_desired_vs_running]

  project      = var.gcp_project_id
  display_name = "Version drift: worker(s) stuck on a non-desired image"
  combiner     = "OR"
  severity     = "WARNING"

  conditions {
    display_name = "desired_vs_running > 0 for ${local.version_drift_running_window}"

    condition_threshold {
      filter = join(" AND ", [
        "resource.type = \"global\"",
        "resource.labels.project_id = \"${var.gcp_project_id}\"",
        "metric.type = \"${local.version_drift_desired_vs_running_metric_type}\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = local.version_drift_running_window

      aggregations {
        alignment_period     = "900s"
        per_series_aligner   = "ALIGN_MAX"
        cross_series_reducer = "REDUCE_MAX"
        group_by_fields      = ["metric.label.profile_id"]
      }

      trigger {
        count = 1
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.team_email.id]

  alert_strategy {
    auto_close = "1800s"
  }

  documentation {
    content = join("\n", [
      "A live run against a Workload profile has been reporting an image digest other than what's",
      "pinned on the profile's latest revision for over ${local.version_drift_running_window}.",
      "Convergence is broken: the worker either can't or won't pick up the desired image.",
      "",
      "Check which profile via the metric's `profile_id` label, then check that profile's live runs:",
      "  workload admin runs list --profile <profile-id>",
      "",
      "Common causes: a worker that can't restart on its own (no supervisor/service unit), a claim",
      "stuck retrying against a bad grant, or a genuinely long-running job that started before a",
      "rollout and has simply outlived the drain window (bump the window if that's expected here).",
    ])
    mime_type = "text/markdown"
  }
}

resource "google_monitoring_alert_policy" "version_drift_published_unrolled" {
  depends_on = [google_monitoring_metric_descriptor.version_drift_published_vs_desired]

  project      = var.gcp_project_id
  display_name = "Version drift: image published but not rolled into a profile"
  combiner     = "OR"
  severity     = "WARNING"

  conditions {
    display_name = "published_vs_desired > 0 for ${local.version_drift_published_window}"

    condition_threshold {
      filter = join(" AND ", [
        "resource.type = \"global\"",
        "resource.labels.project_id = \"${var.gcp_project_id}\"",
        "metric.type = \"${local.version_drift_published_vs_desired_metric_type}\"",
      ])
      comparison      = "COMPARISON_GT"
      threshold_value = 0
      duration        = local.version_drift_published_window

      aggregations {
        alignment_period     = "900s"
        per_series_aligner   = "ALIGN_MAX"
        cross_series_reducer = "REDUCE_MAX"
        group_by_fields      = ["metric.label.profile_id"]
      }

      trigger {
        count = 1
      }
    }
  }

  notification_channels = [google_monitoring_notification_channel.team_email.id]

  alert_strategy {
    auto_close = "1800s"
  }

  documentation {
    content = join("\n", [
      "A Workload profile's `docker_image` tag now resolves to a digest that differs from the one",
      "pinned on its latest revision, and has for over ${local.version_drift_published_window} — CI",
      "published a new image but nobody created a new profile revision to pin it. This is the exact",
      "signal missing during the stale-worker incident: without it, published-but-unrolled can sit",
      "silently for days.",
      "",
      "Check which profile via the metric's `profile_id` label, then either pin the new digest with a",
      "new revision (console: Profiles > the profile > Update, or `workload admin profiles update`),",
      "or, if the new image was published deliberately ahead of a later rollout, treat this as",
      "expected until the revision is created.",
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
