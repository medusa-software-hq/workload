package software.medusa.workload.runtime

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// The set of image repositories `workload worker run` has ever pulled through this environment's
// config dir. `workload worker prune` (M7-00) needs it because Docker gives no "which repos does
// workload own" query, and the containers that would reveal them get reaped: images carry no
// workload label of their own, so ownership has to be remembered here, next to the rest of the
// environment-partitioned state. Repository names only — never a secret — but kept inside the 0700
// config dir at 0600 for consistency with config.json.
private const val managedReposFileName = "gc-repos.json"

private val gcJson = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
}

@Serializable private data class ManagedRepos(val repositories: Set<String> = emptySet())

internal fun managedReposFile(dir: Path): Path = dir.resolve(managedReposFileName)

/** The repositories recorded so far, or empty if none/unreadable — never throws. */
fun loadManagedRepos(dir: Path): Set<String> {
  val file = managedReposFile(dir)
  if (!Files.exists(file)) return emptySet()
  return runCatching { gcJson.decodeFromString<ManagedRepos>(Files.readString(file)).repositories }
      .getOrDefault(emptySet())
}

/**
 * Adds [repository] to the recorded set (best-effort; a write failure is swallowed — GC bookkeeping
 * must never break a run). A no-op when it's already present, so the common re-run case does no
 * write. Creates the config dir at 0700 / the file at 0600 if missing, mirroring [saveConfig].
 */
internal fun recordManagedRepo(dir: Path, repository: String) {
  runCatching {
    val current = loadManagedRepos(dir)
    if (repository in current) return
    if (!Files.exists(dir)) {
      runCatching {
            Files.createDirectory(
                dir,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
          }
          .getOrElse { Files.createDirectories(dir) }
    }
    val file = managedReposFile(dir)
    if (!Files.exists(file)) {
      runCatching {
            Files.createFile(
                file,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
            )
          }
          .getOrElse { Files.createFile(file) }
    }
    Files.writeString(
        file,
        gcJson.encodeToString(ManagedRepos((current + repository).toSortedSet())),
    )
  }
}
