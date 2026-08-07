package software.medusa.workload.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Crashloop detection (M7 automated-rollout): more than [crashloopThreshold] abnormal exits of the
 * same (profile, digest) inside [crashloopWindow] and the agent stops restarting it and holds
 * instead — reported as `crashloop` rather than thrashing forever on a broken image. Deliberately
 * hold, not auto-revert: the fleet is too small to trust an automatic rollback decision, so a human
 * gets paged instead (see [AgentAssignmentState.CRASHLOOP_HOLD]).
 *
 * Unlike the drain-started marker (stamped on the container itself, see `AgentReconciler.kt`'s
 * [drainStartedAt]), a container's exit history doesn't survive its own removal — each crash gets a
 * fresh container next tick — so it can't live on the container. It's kept in a small local state
 * file instead, next to `gc-repos.json`'s precedent for exactly this kind of "Docker has nowhere to
 * put this" bookkeeping.
 */
const val crashloopThreshold = 5

val crashloopWindow = 10.minutes

/** One (profile, digest)'s recent abnormal-exit history. */
@Serializable
data class CrashloopRecord(
    val profileId: String,
    val digest: String,
    val failureEpochSeconds: List<Long> = emptyList(),
    val holding: Boolean = false,
)

@Serializable
private data class CrashloopStateFile(val records: List<CrashloopRecord> = emptyList())

private const val crashloopFileName = "crashloop-state.json"

private val crashloopJson = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
}

internal fun crashloopStateFile(dir: Path): Path = dir.resolve(crashloopFileName)

/** The crashloop records recorded so far, or empty if none/unreadable — never throws. */
fun loadCrashloopRecords(dir: Path): List<CrashloopRecord> {
  val file = crashloopStateFile(dir)
  if (!Files.exists(file)) return emptyList()
  return runCatching {
        crashloopJson.decodeFromString<CrashloopStateFile>(Files.readString(file)).records
      }
      .getOrDefault(emptyList())
}

/**
 * Persists [records] (best-effort; a write failure is swallowed — crashloop bookkeeping must never
 * break a reconcile pass). Creates the config dir at 0700 / the file at 0600 if missing, mirroring
 * [recordManagedRepo].
 */
fun saveCrashloopRecords(dir: Path, records: List<CrashloopRecord>) {
  runCatching {
    if (!Files.exists(dir)) {
      runCatching {
            Files.createDirectory(
                dir,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
          }
          .getOrElse { Files.createDirectories(dir) }
    }
    val file = crashloopStateFile(dir)
    if (!Files.exists(file)) {
      runCatching {
            Files.createFile(
                file,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
          }
          .getOrElse { Files.createFile(file) }
    }
    Files.writeString(file, crashloopJson.encodeToString(CrashloopStateFile(records)))
  }
}

/**
 * Pure: folds one more abnormal exit of (profileId, digest) into [records] at [nowEpochSeconds],
 * pruning failures older than [crashloopWindow] first, then holds if what's left exceeds
 * [crashloopThreshold]. A record already [CrashloopRecord.holding] stays holding regardless of
 * whether its pruned window still exceeds the threshold — hold is a one-way door for a given
 * (profile, digest); only a new digest (a fresh record) or the profile dropping out of the desired
 * set (see [pruneCrashloopRecords]) clears it.
 */
fun recordCrashloopFailure(
    records: List<CrashloopRecord>,
    profileId: String,
    digest: String,
    nowEpochSeconds: Long,
): List<CrashloopRecord> {
  val existing = records.find { it.profileId == profileId && it.digest == digest }
  val prunedTimestamps =
      (existing?.failureEpochSeconds.orEmpty() + nowEpochSeconds).filter {
        nowEpochSeconds - it <= crashloopWindow.inWholeSeconds
      }
  val holding = (existing?.holding ?: false) || prunedTimestamps.size > crashloopThreshold
  val updated = CrashloopRecord(profileId, digest, prunedTimestamps, holding)
  return records.filterNot { it.profileId == profileId && it.digest == digest } + updated
}

/** Whether (profileId, digest) is currently held — the agent must not (re)start it this tick. */
fun isCrashloopHeld(records: List<CrashloopRecord>, profileId: String, digest: String): Boolean =
    records.any {
      it.profileId == profileId && it.digest == digest && it.holding
    }

/**
 * Drops every record whose (profileId, digest) no longer matches [desiredDigestsByProfile] — a
 * profile that's been unassigned, or bumped to a new digest, doesn't need its old history kept
 * around forever. This is also what actually clears a hold: once the desired digest for a held
 * profile changes, its old (profile, digest) record — and the hold with it — is dropped, and the
 * new digest starts with a clean record.
 */
fun pruneCrashloopRecords(
    records: List<CrashloopRecord>,
    desiredDigestsByProfile: Map<String, String>,
): List<CrashloopRecord> = records.filter { desiredDigestsByProfile[it.profileId] == it.digest }
