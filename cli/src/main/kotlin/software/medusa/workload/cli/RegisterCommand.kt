package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt

private const val pollTimeoutSeconds = 600L
private const val pollIntervalStartSeconds = 2L
private const val pollIntervalMaxSeconds = 15L

class RegisterCommand : CliktCommand(name = "register") {
  private val name by option("--name", help = "Defaults to <user>-<hostname>")
  private val brokerUrl by
      option("--broker-url", envvar = "WORKER_BROKER_URL")
          .prompt("Broker URL (e.g. https://api.example.com/<uuid>)")
  private val force by option("--force").flag(default = false)

  override fun run() {
    if (!force && loadConfig() != null) {
      throw PrintMessage(
          "A config already exists at ${configFile()}. Use --force to overwrite it.",
          statusCode = 1,
          printError = true,
      )
    }

    val workerName = name ?: defaultWorkerName()

    val registered =
        try {
          registerWorker(brokerUrl, workerName)
        } catch (e: WorkerApiException) {
          throw PrintMessage("Registration failed: ${e.message}", statusCode = 1, printError = true)
        }

    saveConfig(
        WorkloadConfig(
            brokerBaseUrl = brokerUrl,
            workerId = registered.workerId,
            workerSecret = registered.workerSecret,
            workerName = workerName,
        )
    )

    echo("Registered as '$workerName'.")
    echo()
    echo("Confirmation code: ${registered.confirmationCode}")
    echo("Give this code to an admin to approve this worker in the console.")
    echo()
    echo("Waiting for approval (up to ${pollTimeoutSeconds / 60} minutes)...")

    when (
        pollUntilDecided(
            brokerUrl,
            registered.workerId,
            registered.workerSecret,
            pollTimeoutSeconds,
        )
    ) {
      PollOutcome.APPROVED -> echo("Approved.")
      PollOutcome.REJECTED ->
          throw PrintMessage(
              "Registration was rejected. Ask an admin for details, then run 'workload register --force' to try again.",
              statusCode = 1,
              printError = true,
          )
      PollOutcome.TIMED_OUT ->
          throw PrintMessage(
              "Timed out waiting for approval. Run 'workload status' later to check, or 'workload register --force' to start over.",
              statusCode = 1,
              printError = true,
          )
    }
  }
}

private fun defaultWorkerName(): String {
  val user = System.getProperty("user.name") ?: "worker"
  val host = localHostname() ?: "unknown-host"
  return "$user-$host"
}

private enum class PollOutcome {
  APPROVED,
  REJECTED,
  TIMED_OUT,
}

private fun pollUntilDecided(
    brokerUrl: String,
    workerId: String,
    workerSecret: String,
    timeoutSeconds: Long,
): PollOutcome {
  val deadline = System.nanoTime() + timeoutSeconds * 1_000_000_000L
  var intervalSeconds = pollIntervalStartSeconds

  while (System.nanoTime() < deadline) {
    try {
      val status = fetchSelfStatus(brokerUrl, workerId, workerSecret)
      if (status.status == "active") return PollOutcome.APPROVED
      // Otherwise "pending" — keep waiting.
    } catch (e: WorkerApiException) {
      // Credentials that were valid moments ago are now unauthorized: the worker left the
      // pollable set. SelfStatusService can't distinguish an explicit rejection from a
      // TTL-expired pending worker (both converge on "rejected"), so neither can we.
      return PollOutcome.REJECTED
    } catch (e: java.io.IOException) {
      // A transient network blip — keep retrying until the deadline.
    }

    Thread.sleep(intervalSeconds * 1000)
    intervalSeconds = minOf(intervalSeconds * 2, pollIntervalMaxSeconds)
  }

  return PollOutcome.TIMED_OUT
}
