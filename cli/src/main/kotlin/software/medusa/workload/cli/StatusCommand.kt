package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage

class StatusCommand : CliktCommand(name = "status") {
  private val env by requireEnvironment()

  override fun run() {
    val config =
        loadConfig(env.configDir)
            ?: throw PrintMessage(
                "No config found at ${configFile(env.configDir)}. Run 'workload worker register' first.",
                statusCode = 1,
                printError = true,
            )

    val status =
        try {
          fetchSelfStatus(env.apiBaseUrl, config.workerId, config.workerSecret)
        } catch (e: WorkerApiException) {
          throw PrintMessage(
              "Status poll failed: ${e.message}. The worker may have been rejected or revoked.",
              statusCode = 1,
              printError = true,
          )
        }

    echo("Name:   ${status.name}")
    echo("Status: ${status.status}")
    if (status.status == "active") {
      if (status.grantedProfileIds.isEmpty()) {
        echo("Granted profiles: none")
      } else {
        echo("Granted profiles: ${status.grantedProfileIds.joinToString(", ")}")
      }
    }
  }
}
