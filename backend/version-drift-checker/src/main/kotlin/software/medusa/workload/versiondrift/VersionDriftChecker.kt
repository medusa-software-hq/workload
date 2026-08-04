package software.medusa.workload.versiondrift

import java.time.Instant
import org.slf4j.LoggerFactory
import software.medusa.workload.server.FleetStore
import software.medusa.workload.server.ImageDigestResolver
import software.medusa.workload.server.ImageStatus
import software.medusa.workload.server.RunFilter

private val logger = LoggerFactory.getLogger(VersionDriftChecker::class.java)

/**
 * One profile's drift verdict for a single check run. [runningMismatch] is true when a *live* run
 * against this profile is reporting an image digest other than [desiredDigest] — the "someone is
 * stuck on an old image" signal. [publishedMismatch] is true when the profile's `docker_image` tag
 * currently resolves to a digest other than [desiredDigest] — the "someone published but nobody
 * rolled" signal that was missing during the stale-worker incident. Both are read fresh on every
 * check; persistence (the "for longer than N hours" part of the alert) is left to the Cloud
 * Monitoring alert policy's `duration`, not tracked here — see backend/infra/gcp-monitoring.tf.
 */
data class ProfileDriftResult(
    val profileId: String,
    val desiredDigest: String,
    val runningMismatch: Boolean,
    val publishedMismatch: Boolean,
)

/**
 * Phase 0 of the automated-rollout epic (workload#126): compares, per image-based profile, the
 * **desired** digest (pinned in its latest revision), the **running** digest (self-reported by live
 * workers via their run's `imageDigest` — no new worker code required, it's already collected by
 * `POST /worker/v2/runs`), and the **latest-published** digest (re-resolving the profile's image
 * tag against the registry, the same lookup `ResolveImage`/`VerifyProfile` already do). A mismatch
 * on either comparison is written as a gauge via [metricWriter] on every run; the *alerting*
 * threshold ("broken for longer than a drain-plausible window") lives in the Cloud Monitoring alert
 * policy, so this class only ever reports the current-instant verdict.
 *
 * Profiles with no image (pure exec/env), archived profiles, and revisions whose image hasn't
 * resolved to a digest yet ([ImageStatus.RESOLVED] didn't happen) are skipped — there is no digest
 * to compare against.
 */
class VersionDriftChecker(
    private val fleetStore: FleetStore,
    private val imageDigestResolver: ImageDigestResolver,
    private val metricWriter: DriftMetricWriter,
) {
  suspend fun run(now: Instant = Instant.now()): List<ProfileDriftResult> {
    val results = mutableListOf<ProfileDriftResult>()

    for (profile in fleetStore.listProfiles()) {
      if (profile.archived) continue

      val revision = fleetStore.getLatestProfileRevision(profile.profileId) ?: continue
      val dockerImage = revision.dockerImage
      val desired = revision.dockerImageDigest
      if (dockerImage == null || desired == null || revision.imageStatus != ImageStatus.RESOLVED) {
        continue
      }

      val liveRuns =
          fleetStore.listRuns(RunFilter(profileId = profile.profileId, liveOnly = true), now = now)
      val runningMismatch = liveRuns.any { it.imageDigest != null && it.imageDigest != desired }

      val resolution = imageDigestResolver.resolve(revision.targetServiceAccount, dockerImage)
      val publishedMismatch =
          resolution.status == ImageStatus.RESOLVED &&
              resolution.digest != null &&
              resolution.digest != desired
      if (resolution.status != ImageStatus.RESOLVED) {
        // Can't tell right now (registry hiccup, transient auth failure) — log it, but don't claim
        // a verdict either way; the next scheduled run tries again.
        logger.warn(
            "could not resolve current published digest for profile {} ({}): {}",
            profile.profileId.value,
            dockerImage,
            resolution.detail,
        )
      }

      val result =
          ProfileDriftResult(
              profileId = profile.profileId.value,
              desiredDigest = desired,
              runningMismatch = runningMismatch,
              publishedMismatch = publishedMismatch,
          )
      results += result
      metricWriter.write(result, now)
    }

    return results
  }
}
