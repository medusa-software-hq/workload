package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val configDirName = "ms-workload"
private const val configFileName = "config.json"

private val json = Json {
  ignoreUnknownKeys = true
  prettyPrint = true
}

// No broker URL here: the CLI talks to the one backend endpoint ([BuildConfig.apiBaseUrl]), not a
// per-worker "broker". Old configs carrying a `brokerBaseUrl` still load (unknown keys are
// ignored).
@Serializable
data class WorkloadConfig(
    val workerId: String,
    val workerSecret: String,
    val workerName: String,
)

/** `$XDG_CONFIG_HOME/ms-workload/`, falling back to `~/.config/ms-workload/` — also on macOS. */
fun configDir(
    xdgConfigHome: String? = System.getenv("XDG_CONFIG_HOME"),
    userHome: String = System.getProperty("user.home"),
): Path {
  val base =
      if (!xdgConfigHome.isNullOrBlank()) {
        Path.of(xdgConfigHome)
      } else {
        Path.of(userHome, ".config")
      }
  return base.resolve(configDirName)
}

fun configFile(dir: Path = configDir()): Path = dir.resolve(configFileName)

fun loadConfig(dir: Path = configDir()): WorkloadConfig? {
  val file = configFile(dir)
  if (!Files.exists(file)) return null
  return json.decodeFromString(Files.readString(file))
}

/**
 * Writes [config] with directory permissions 0700 and file permissions 0600 — set atomically at
 * creation where the platform supports POSIX permissions, never as a follow-up chmod that would
 * leave a window with looser permissions.
 */
fun saveConfig(config: WorkloadConfig, dir: Path = configDir()) {
  if (!Files.exists(dir)) {
    runCatching {
          Files.createDirectory(
              dir,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
          )
        }
        .getOrElse { Files.createDirectories(dir) }
  }

  val file = configFile(dir)
  Files.deleteIfExists(file)
  runCatching {
        Files.createFile(
            file,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
        )
      }
      .getOrElse { Files.createFile(file) }
  Files.writeString(file, json.encodeToString(config))
}

fun deleteConfig(dir: Path = configDir()) {
  Files.deleteIfExists(configFile(dir))
}
