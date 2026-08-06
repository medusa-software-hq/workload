package software.medusa.workload.agent

import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Minimal env/identity loading, deliberately duplicated in miniature from the CLI's
 * `Environment`/`Config` rather than pulled in as a dependency: `libraries/workload-runtime` is
 * kept CLI-free by design (settings.gradle.kts), and this daemon only ever needs three things — the
 * broker URL, the config dir, and the worker credential already written there by `workload worker
 * register` at enrollment — not the CLI's staging/local/OAuth machinery.
 */
private val json = Json { ignoreUnknownKeys = true }

private const val configDirName = "ms-workload"

@Serializable
internal data class WorkloadConfig(
    val workerId: String,
    val workerSecret: String,
    val workerName: String,
)

internal fun brokerBaseUrl(environment: String?): String =
    when (environment?.trim()?.lowercase()?.ifBlank { null }) {
      null,
      "prod",
      "production" -> "https://api.workload-baseline.medusa.software"
      "staging" -> "https://api.workload-baseline-staging.medusa.software"
      else -> environment // an explicit http(s) URL, e.g. for a local/dev backend
    }

internal fun configDir(environment: String?): Path {
  val xdg = System.getenv("XDG_CONFIG_HOME")?.ifBlank { null }
  val base = if (xdg != null) Path.of(xdg) else Path.of(System.getProperty("user.home"), ".config")
  val label =
      when (environment?.trim()?.lowercase()?.ifBlank { null }) {
        null,
        "prod",
        "production" -> "prod"
        "staging" -> "staging"
        else -> "local"
      }
  return base.resolve(configDirName).resolve(label)
}

internal fun loadWorkloadConfig(dir: Path): WorkloadConfig? {
  val file = dir.resolve("config.json")
  if (!java.nio.file.Files.exists(file)) return null
  return runCatching { json.decodeFromString<WorkloadConfig>(java.nio.file.Files.readString(file)) }
      .getOrNull()
}
