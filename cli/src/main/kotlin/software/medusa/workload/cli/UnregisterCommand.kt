package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option

class UnregisterCommand : CliktCommand(name = "unregister") {
  private val yes by option("--yes", "-y", help = "Skip the confirmation prompt").flag()

  override fun run() {
    val config =
        loadConfig()
            ?: throw PrintMessage(
                "No config found at ${configFile()}. Nothing to do.",
                statusCode = 1,
                printError = true,
            )

    if (!yes) {
      echo(
          "Delete local config for worker '${config.workerName}' at ${configFile()}? [y/N] ",
          trailingNewline = false,
      )
      val answer = readlnOrNull()?.trim()?.lowercase()
      if (answer != "y" && answer != "yes") {
        throw PrintMessage("Aborted.", statusCode = 1, printError = true)
      }
    }

    deleteConfig()
    echo("Local config deleted.")
    echo(
        "This does not revoke the worker on the server — revoke it in the console if it should no longer have access."
    )
  }
}
