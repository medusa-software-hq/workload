package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.requireObject
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required

class TokenCommand : CliktCommand(name = "token") {
  private val env by requireObject<Environment>()
  private val profileId by
      option("--profile", "-p", help = "The profile to claim a token for").required()

  override fun run() {
    val config = loadConfigOrFail(env)
    val token =
        try {
          claimToken(env.apiBaseUrl, config.workerId, config.workerSecret, profileId)
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

internal fun tokenClaimErrorMessage(e: WorkerApiException, profileId: String): String =
    if (e.statusCode == 404) {
      // The v2 worker plane returns a bare 404 for a wrong URL *and* for any rejected credential
      // (revoked, or a worker that never activated) — it's deliberately indistinguishable.
      "The broker returned 404. That means either a wrong broker URL, or a credential the broker " +
          "rejected — this worker may have been revoked or never activated. Check " +
          "'workload worker status', and re-register with 'workload worker register --force' if needed."
    } else
        when (e.errorCode) {
          "unauthorized" ->
              "Worker is not approved (pending, rejected, or revoked). Run 'workload worker status' to check."
          "profile_not_found" ->
              "No such profile '$profileId'. Check the profile ID, or ask an admin to grant it to you."
          "profile_archived" ->
              "Profile '$profileId' has been archived and can no longer be claimed."
          "not_granted" ->
              "You don't have access to profile '$profileId'. Ask an admin to grant it to you."
          "failed_to_mint_token" ->
              "The broker failed to mint a token — likely an IAM misconfiguration on the target service account. Contact an admin."
          "not_verified" ->
              "Profile '$profileId' has an unverified revision and can't be claimed. Ask an admin to re-verify it."
          "image_unresolvable" ->
              "Profile '$profileId' has an image whose digest couldn't be resolved, so it can't be claimed. " +
                  "Its target service account likely lacks roles/artifactregistry.reader on the image's repository " +
                  "(see the workload-impersonation module's artifact_repository_id input). Ask an admin to re-verify it."
          else -> "Token claim failed: ${e.errorCode}"
        }
