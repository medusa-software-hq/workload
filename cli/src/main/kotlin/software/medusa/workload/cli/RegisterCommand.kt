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
  private val enrollmentToken by
      option(
              "--enrollment-token",
              envvar = "WORKER_ENROLLMENT_TOKEN",
              help = "One-time wle_ token an admin generated for you in the console",
          )
          .prompt("Enrollment token", hideInput = true)
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
    val apiBaseUrl = BuildConfig.apiBaseUrl

    val registered =
        try {
          registerWorker(apiBaseUrl, workerName, enrollmentToken.trim())
        } catch (e: WorkerApiException) {
          throw PrintMessage(registerErrorMessage(e), statusCode = 1, printError = true)
        }

    saveConfig(
        WorkloadConfig(
            workerId = registered.workerId,
            workerSecret = registered.workerSecret,
            workerName = workerName,
        )
    )

    echo("Registered as '$workerName'.")
    echo()
    echo("Waiting for the worker to become active (up to ${pollTimeoutSeconds / 60} minutes)...")

    when (
        pollUntilDecided(
            apiBaseUrl,
            registered.workerId,
            registered.workerSecret,
            pollTimeoutSeconds,
        )
    ) {
      PollOutcome.APPROVED -> echo("Active.")
      PollOutcome.REJECTED ->
          throw PrintMessage(
              "Registration was rejected. Ask an admin for details, then run 'workload worker register --force' to try again.",
              statusCode = 1,
              printError = true,
          )
      PollOutcome.TIMED_OUT ->
          throw PrintMessage(
              "Timed out waiting for approval. Run 'workload worker status' later to check, or 'workload worker register --force' to start over.",
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

/**
 * Turns a failed v2 registration into an actionable message. A 404 is deliberately ambiguous on the
 * server side (the worker plane is unprobeable), so the CLI spells out both things it can mean.
 */
fun registerErrorMessage(e: WorkerApiException): String =
    if (e.statusCode == 404) {
      "Registration failed (404). That usually means an enrollment token that is invalid, already " +
          "used, or expired — the broker returns the same 404 for all of them. Ask an admin for a " +
          "fresh token if you're unsure it's still good."
    } else {
      "Registration failed: ${e.message}"
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
