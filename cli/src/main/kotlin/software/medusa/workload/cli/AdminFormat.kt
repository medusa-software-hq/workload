package software.medusa.workload.cli

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The mutable fields of a profile revision — the shape `admin profiles show --json` emits and
 * `create`/`update` will consume. Server-assigned fields (id, revision number, timestamps,
 * verification, resolved digest) are deliberately absent: they're outputs, not inputs.
 */
@Serializable
data class ProfileRevisionSpec(
    val targetServiceAccount: String = "",
    val note: String = "",
    val dockerImage: String = "",
    val envVars: Map<String, String> = emptyMap(),
    val secretEnvVars: Map<String, String> = emptyMap(),
    // The payload-declared supervisor stop timeout for a claim of this revision, e.g. "6h". Empty
    // means "supervisor default".
    val drainDeadline: String = "",
)

/** Pretty JSON for the round-trippable spec; encodeDefaults so empty maps/strings stay visible. */
val specJson = Json {
  prettyPrint = true
  encodeDefaults = true
}

/**
 * Lenient reader for a spec supplied by the user: unknown keys are tolerated, all fields optional.
 */
val specDecodeJson = Json { ignoreUnknownKeys = true }

/**
 * Compact JSON for machine consumption, e.g. `admin runs list --json` (scripts like roll-worker).
 */
val runsJson = Json { encodeDefaults = true }

fun specFromRevision(revision: AdminProfileRevision): ProfileRevisionSpec =
    ProfileRevisionSpec(
        targetServiceAccount = revision.targetServiceAccount,
        note = revision.note,
        dockerImage = revision.dockerImage,
        envVars = revision.envVars,
        secretEnvVars = revision.secretEnvVars,
        drainDeadline = revision.drainDeadline,
    )

/**
 * `2026-07-17T14:08:44.512979Z` → `2026-07-17 14:08 UTC`; blanks become `—`, unparseables pass
 * through.
 */
fun formatTimestamp(iso: String): String {
  if (iso.isBlank()) return "—"
  return runCatching {
        Instant.parse(iso).atZone(ZoneOffset.UTC).format(timestampFormatter) + " UTC"
      }
      .getOrDefault(iso)
}

private val timestampFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** `WORKER_STATUS_ACTIVE` → `active`, `VERIFICATION_STATUS_BINDING_MISSING` → `binding-missing`. */
fun shortEnum(value: String): String =
    if (value.isBlank()) "—"
    else value.substringAfterLast("_STATUS_", value).lowercase().replace('_', '-')

/** `RUN_STATE_RUNNING` → `running`, `RUN_KIND_EXEC` → `exec`; blank → `—`. */
fun shortRunEnum(value: String): String =
    if (value.isBlank()) "—" else value.substringAfterLast('_').lowercase()

/**
 * `AGENT_ASSIGNMENT_STATE_CRASHLOOP_HOLD` → `crashloop-hold`, `AGENT_ASSIGNMENT_STATE_DRAINING` →
 * `draining`; blank → `—`. Can't use [shortRunEnum]'s last-underscore trick — several of these
 * states are themselves multiple words (`CRASHLOOP_HOLD`).
 */
fun shortAssignmentState(value: String): String =
    if (value.isBlank()) "—"
    else value.substringAfter("AGENT_ASSIGNMENT_STATE_", value).lowercase().replace('_', '-')

/** `sha256:deadbeef...` → `deadbeef1234`; blank → `—`. */
fun shortDigest(digest: String): String = digest.removePrefix("sha256:").take(12).ifBlank { "—" }

/**
 * A worker's fleet column for `admin workers list` (M7 automated rollout): one line summarizing
 * every profile's [AdminAssignmentStatus] — the *worst* state wins (crashloop-hold, then draining,
 * then replacing), since that's what an operator scanning the list needs to notice first. `—` for a
 * worker whose agent hasn't reported yet (a pre-M7 CLI-only worker, or one that just registered).
 */
fun formatFleetSummary(
    statuses: List<AdminAssignmentStatus>,
    now: Instant = Instant.now(),
): String {
  if (statuses.isEmpty()) return "—"
  val held = statuses.filter { shortAssignmentState(it.state) == "crashloop-hold" }
  if (held.isNotEmpty()) return "⚠ crashloop-hold: ${held.joinToString(",") { it.profileId }}"
  val draining = statuses.filter { shortAssignmentState(it.state) == "draining" }
  if (draining.isNotEmpty()) {
    return draining.joinToString(", ") {
      "draining: ${it.profileId} (${formatRelative(it.since, now)})"
    }
  }
  val replacing = statuses.filter { shortAssignmentState(it.state) == "replacing" }
  if (replacing.isNotEmpty()) return "replacing: ${replacing.joinToString(",") { it.profileId }}"
  return "converged (${statuses.size})"
}

/**
 * A coarse "how long ago" for a timestamp: `30s ago`, `4m ago`, `2h ago`, `3d ago`. Blank →
 * `never`, unparseable → passed through. [now] is injectable for tests.
 */
fun formatRelative(iso: String, now: Instant = Instant.now()): String {
  if (iso.isBlank()) return "never"
  return runCatching {
        val seconds = java.time.Duration.between(Instant.parse(iso), now).seconds.coerceAtLeast(0)
        when {
          seconds < 60 -> "${seconds}s ago"
          seconds < 3600 -> "${seconds / 60}m ago"
          seconds < 86_400 -> "${seconds / 3600}h ago"
          else -> "${seconds / 86_400}d ago"
        }
      }
      .getOrDefault(iso)
}

/**
 * A run's elapsed time — `[ended] - [started]`, or `now - started` when [ended] is blank (still
 * going). `3h12m` / `4m01s` / `45s`; blank/unparseable start → `—`. [now] is injectable for tests.
 */
fun formatRunDuration(started: String, ended: String, now: Instant = Instant.now()): String {
  if (started.isBlank()) return "—"
  return runCatching {
        val from = Instant.parse(started)
        val to = if (ended.isBlank()) now else Instant.parse(ended)
        val total = java.time.Duration.between(from, to).seconds.coerceAtLeast(0)
        val h = total / 3600
        val m = (total % 3600) / 60
        val s = total % 60
        when {
          h > 0 -> "${h}h${m}m"
          m > 0 -> "${m}m%02ds".format(s)
          else -> "${s}s"
        }
      }
      .getOrDefault("—")
}
