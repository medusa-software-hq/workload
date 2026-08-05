package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import kotlinx.coroutines.runBlocking
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig
import software.medusa.workload.docker.SystemDf
import software.medusa.workload.runtime.imageRefsInUse
import software.medusa.workload.runtime.loadManagedRepos
import software.medusa.workload.runtime.reason
import software.medusa.workload.runtime.sweepRepository
import software.medusa.workload.runtime.workloadProfileLabel

/** `1.0 KB`, `12.3 MB`, `4.2 GB` — a compact size the way `docker system df` renders bytes. */
internal fun formatBytes(bytes: Long): String {
  if (bytes < 1024) return "$bytes B"
  val units = listOf("KB", "MB", "GB", "TB")
  var value = bytes.toDouble() / 1024
  var unit = 0
  while (value >= 1024 && unit < units.size - 1) {
    value /= 1024
    unit++
  }
  return String.format("%.1f %s", value, units[unit])
}

/** One line summarising a `df` snapshot: image count + on-disk bytes, and container count. */
internal fun formatDfLine(df: SystemDf): String =
    "images: ${df.imageCount} (${formatBytes(df.layersSize)})  containers: ${df.containerCount}"

class PruneCommand : CliktCommand(name = "prune") {
  override fun help(context: Context) =
      "Reclaim disk on this machine: remove workload's own exited containers and the superseded, " +
          "digest-pinned images of every profile this machine has run — never touching images or " +
          "containers that aren't workload's, and never an image still in use."

  private val env by requireEnvironment()

  override fun run() {
    DockerConnector(DockerConnectorConfig.fromEnvironment()).use { connector ->
      val before =
          try {
            runBlocking { connector.systemDf() }
          } catch (e: DockerConnectionException) {
            throw PrintMessage(
                e.message ?: "Cannot reach the Docker daemon",
                statusCode = 1,
                printError = true,
            )
          }
      echo("Before:  ${formatDfLine(before)}", err = true)

      // Reap our exited containers first, so the images they held stop counting as "in use" and can
      // then be swept below. Prune never touches a running container, ours or the user's.
      val reaped =
          runCatching {
                runBlocking { connector.containers.prune(labelKeys = listOf(workloadProfileLabel)) }
              }
              .getOrElse {
                echo("workload: could not reap exited containers (${it.reason()})", err = true)
                null
              }
      reaped?.let { echo("Reaped ${it.deletedCount} exited workload container(s).", err = true) }

      val repos = loadManagedRepos(env.configDir)
      if (repos.isEmpty()) {
        echo(
            "No workload-managed repositories recorded on this machine — nothing to sweep.",
            err = true,
        )
      } else {
        val inUse = runBlocking { imageRefsInUse(connector) }
        var totalDeleted = 0
        for (repository in repos) {
          val deleted = runBlocking {
            sweepRepository(
                connector = connector,
                repository = repository,
                keepDigests = emptySet(),
                inUse = inUse,
                warn = { echo(it, err = true) },
                onKept = { echo("  $repository: $it", err = true) },
            )
          }
          totalDeleted += deleted
          if (deleted > 0) {
            echo("Swept $deleted superseded image(s) from $repository.", err = true)
          }
        }
        echo(
            "Removed $totalDeleted superseded image(s) across ${repos.size} repositor" +
                (if (repos.size == 1) "y." else "ies."),
            err = true,
        )
      }

      val after = runCatching { runBlocking { connector.systemDf() } }.getOrNull()
      if (after != null) {
        echo("After:   ${formatDfLine(after)}", err = true)
        val freed = before.layersSize - after.layersSize
        if (freed > 0) {
          echo("Freed ${formatBytes(freed)}.", err = true)
        }
      }
    }
  }
}
