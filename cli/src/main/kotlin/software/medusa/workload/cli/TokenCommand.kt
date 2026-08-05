package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import software.medusa.workload.runtime.SecretBrokerAuth
import software.medusa.workload.runtime.WorkerApiException
import software.medusa.workload.runtime.claimToken
import software.medusa.workload.runtime.tokenClaimErrorMessage

class TokenCommand : CliktCommand(name = "token") {
  private val env by requireEnvironment()
  private val profileId by
      option("--profile", "-p", help = "The profile to claim a token for").required()

  override fun run() {
    val config = loadConfigOrFail(env)
    val token =
        try {
          claimToken(
              env.apiBaseUrl,
              SecretBrokerAuth(config.workerId, config.workerSecret),
              profileId,
          )
        } catch (e: WorkerApiException) {
          throw PrintMessage(
              tokenClaimErrorMessage(e, profileId),
              statusCode = 1,
              printError = true,
          )
        }

    // Only the token itself goes to stdout, so callers can pipe it directly, e.g.
    // `gcloud ... --access-token-file <(workload worker token -p my-profile-1)`. Everything else is
    // context for a human, on stderr.
    echo(token.accessToken)
    echo("Profile:      ${token.profileId} (revision ${token.revision})", err = true)
    echo("Service acct: ${token.serviceAccount}", err = true)
    echo("Expires at:   ${token.expiresAt}", err = true)
  }
}

internal fun loadConfigOrFail(env: Environment): WorkloadConfig =
    loadConfig(env.configDir)
        ?: throw PrintMessage(
            "No config found at ${configFile(env.configDir)}. Run 'workload worker register' first.",
            statusCode = 1,
            printError = true,
        )
