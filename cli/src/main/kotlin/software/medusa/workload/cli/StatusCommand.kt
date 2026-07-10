package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage

class StatusCommand : CliktCommand(name = "status") {
  override fun run() {
    val config =
        loadConfig()
            ?: throw PrintMessage(
                "No config found at ${configFile()}. Run 'workload register' first.",
                statusCode = 1,
                printError = true,
            )

    val status =
        try {
          fetchSelfStatus(config.brokerBaseUrl, config.workerId, config.workerSecret)
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
