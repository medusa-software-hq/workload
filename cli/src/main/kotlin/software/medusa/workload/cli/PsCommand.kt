package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.mordant.terminal.YesNoPrompt
import java.time.Duration
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import software.medusa.workload.docker.ContainerSummary
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig
import software.medusa.workload.docker.DockerConnectorException
import software.medusa.workload.runtime.workloadNetworkLabel
import software.medusa.workload.runtime.workloadProfileLabel
import software.medusa.workload.runtime.workloadRevisionLabel

// Same grace `workload worker run` gives a container on Ctrl-C.
private val reapStopGrace = 10.seconds

/** A row of `workload worker ps`, already reduced to what we print. */
internal data class PsRow(
    val id: String,
    val profile: String,
    val revision: String,
    val state: String,
    val status: String,
    val age: String,
)

/** `3s`, `4m`, `2h`, `5d` — a compact age, the way `docker ps` renders CREATED. */
internal fun formatAge(createdEpochSeconds: Long, now: Instant): String {
  if (createdEpochSeconds <= 0) return "-"
  val elapsed = Duration.between(Instant.ofEpochSecond(createdEpochSeconds), now)
  val seconds = elapsed.seconds
  return when {
    seconds < 0 -> "-"
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m"
    seconds < 86_400 -> "${seconds / 3600}h"
    else -> "${seconds / 86_400}d"
  }
}

/** Projects a container onto its `ms-workload.*` labels; unlabelled fields read as `-`. */
internal fun toPsRow(container: ContainerSummary, now: Instant): PsRow =
    PsRow(
        id = container.id.take(12),
        profile = container.labels[workloadProfileLabel] ?: "-",
        revision = container.labels[workloadRevisionLabel] ?: "-",
        state = container.state ?: "-",
        status = container.status ?: "-",
        age = formatAge(container.created, now),
    )

/** Renders the rows as an aligned table (header included). Empty input yields an empty list. */
internal fun renderPsTable(rows: List<PsRow>): List<String> {
  if (rows.isEmpty()) return emptyList()
  val header = PsRow("CONTAINER", "PROFILE", "REV", "STATE", "STATUS", "AGE")
  val all = listOf(header) + rows
  fun width(select: (PsRow) -> String) = all.maxOf { select(it).length }
  val w =
      listOf<(PsRow) -> String>(
          { it.id },
          { it.profile },
          { it.revision },
          { it.state },
          { it.status },
      )
  val widths = w.map { sel -> width(sel) }
  return all.map { row ->
    listOf(row.id, row.profile, row.revision, row.state, row.status)
        .mapIndexed { i, cell -> cell.padEnd(widths[i]) }
        .joinToString("  ")
        .plus("  ${row.age}")
        .trimEnd()
  }
}

class PsCommand : CliktCommand(name = "ps") {
  override fun help(context: Context) =
      "List containers started by workload on this machine. --reap stops and removes them — " +
          "the way to clean up a container orphaned by a hard kill of 'workload worker run'."

  private val reap by
      option("--reap", help = "Stop and remove the listed containers (asks first)").flag()

  private val yes by option("--yes", "-y", help = "Skip the --reap confirmation").flag()

  override fun run() {
    DockerConnector(DockerConnectorConfig.fromEnvironment()).use { connector ->
      val containers =
          try {
            runBlocking {
              // Every container `workload worker run` creates carries the profile label, so its
              // presence
              // is exactly "workload owns this" — regardless of which profile or worker.
              connector.containers.list(labelKeys = listOf(workloadProfileLabel))
            }
          } catch (e: DockerConnectionException) {
            throw PrintMessage(
                e.message ?: "Cannot reach the Docker daemon",
                statusCode = 1,
                printError = true,
            )
          }

      if (containers.isEmpty()) {
        echo("No workload containers on this machine.", err = true)
        return@use
      }

      val now = Instant.now()
      renderPsTable(containers.map { toPsRow(it, now) }).forEach { echo(it) }

      if (reap) {
        reapAll(connector, containers)
      }
    }
  }

  private fun reapAll(connector: DockerConnector, containers: List<ContainerSummary>) {
    val running = containers.count { it.running }
    echo("", err = true)
    if (running > 0) {
      // A running container may be a live `workload worker run` in another terminal, not an orphan.
      // We
      // can't tell the difference — there's no heartbeat — so say so instead of guessing.
      echo(
          "Warning: $running of these are still running. If a 'workload worker run' is in progress " +
              "elsewhere on this machine, reaping will kill it.",
          err = true,
      )
    }

    if (!yes) {
      val confirmed =
          YesNoPrompt("Stop and remove ${containers.size} container(s)?", terminal, default = false)
              .ask()
      if (confirmed != true) {
        echo("Nothing reaped.", err = true)
        return
      }
    }

    var reaped = 0
    for (container in containers) {
      val outcome = runCatching {
        runBlocking {
          // Stop first so a running container gets its SIGTERM grace rather than a bare SIGKILL;
          // force covers the already-exited ones, where stop is a no-op.
          if (container.running) {
            connector.containers.stop(container.id, reapStopGrace)
          }
          connector.containers.remove(container.id, force = true)
        }
      }
      outcome.fold(
          onSuccess = {
            reaped++
            echo("Reaped ${container.id.take(12)}", err = true)
          },
          onFailure = { e ->
            val reason = (e as? DockerConnectorException)?.message ?: e.toString()
            echo("Could not reap ${container.id.take(12)}: $reason", err = true)
          },
      )
    }
    echo("Reaped $reaped of ${containers.size}.", err = true)

    // Containers are gone now, so any per-run bridge network they held is detachable. Sweep the
    // ones a hard-killed `workload run` orphaned (a live run's network still has its container, so
    // the daemon refuses removal — we let that fail quietly rather than kill an in-flight run).
    reapNetworks(connector)
  }

  private fun reapNetworks(connector: DockerConnector) {
    val networks =
        runCatching {
              runBlocking { connector.networks.list(labelKeys = listOf(workloadNetworkLabel)) }
            }
            .getOrElse {
              return
            }
    if (networks.isEmpty()) return

    var removed = 0
    for (network in networks) {
      runCatching { runBlocking { connector.networks.remove(network.id) } }
          .fold(
              onSuccess = {
                removed++
                echo("Reaped network ${network.name ?: network.id.take(12)}", err = true)
              },
              onFailure = { e ->
                // Typically "network has active endpoints" — a still-running run owns it. Expected.
                val reason = (e as? DockerConnectorException)?.message ?: e.toString()
                echo(
                    "Left network ${network.name ?: network.id.take(12)} (in use?): $reason",
                    err = true,
                )
              },
          )
    }
    echo("Reaped $removed of ${networks.size} network(s).", err = true)
  }
}
