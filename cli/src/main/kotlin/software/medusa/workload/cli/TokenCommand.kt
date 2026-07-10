package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

class TokenCommand : CliktCommand(name = "token") {
  private val profileId by
      option("--profile", "-p", help = "The profile to claim a token for").required()

  override fun run() {
    val config = loadConfigOrFail()
    val token =
        try {
          claimToken(config.brokerBaseUrl, config.workerId, config.workerSecret, profileId)
        } catch (e: WorkerApiException) {
          throw PrintMessage(
              tokenClaimErrorMessage(e, profileId),
              statusCode = 1,
              printError = true,
          )
        }

    // Only the token itself goes to stdout, so callers can pipe it directly, e.g.
    // `gcloud ... --access-token-file <(workload token -p my-profile-1)`. Everything else is
    // context for a human, on stderr.
    echo(token.accessToken)
    echo("Profile:      ${token.profileId} (revision ${token.revision})", err = true)
    echo("Service acct: ${token.serviceAccount}", err = true)
    echo("Expires at:   ${token.expiresAt}", err = true)
  }
}

internal fun loadConfigOrFail(): WorkloadConfig =
    loadConfig()
        ?: throw PrintMessage(
            "No config found at ${configFile()}. Run 'workload register' first.",
            statusCode = 1,
            printError = true,
        )

internal fun tokenClaimErrorMessage(e: WorkerApiException, profileId: String): String =
    when (e.errorCode) {
      "unauthorized" ->
          "Worker is not approved (pending, rejected, or revoked). Run 'workload status' to check."
      "profile_not_found" ->
          "No such profile '$profileId'. Check the profile ID, or ask an admin to grant it to you."
      "profile_archived" -> "Profile '$profileId' has been archived and can no longer be claimed."
      "not_granted" ->
          "You don't have access to profile '$profileId'. Ask an admin to grant it to you."
      "failed_to_mint_token" ->
          "The broker failed to mint a token — likely an IAM misconfiguration on the target service account. Contact an admin."
      else -> "Token claim failed: ${e.errorCode}"
    }
