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
)

/** Pretty JSON for the round-trippable spec; encodeDefaults so empty maps/strings stay visible. */
val specJson = Json {
  prettyPrint = true
  encodeDefaults = true
}

fun specFromRevision(revision: AdminProfileRevision): ProfileRevisionSpec =
    ProfileRevisionSpec(
        targetServiceAccount = revision.targetServiceAccount,
        note = revision.note,
        dockerImage = revision.dockerImage,
        envVars = revision.envVars,
        secretEnvVars = revision.secretEnvVars,
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
