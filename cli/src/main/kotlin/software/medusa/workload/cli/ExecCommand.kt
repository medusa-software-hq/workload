package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.util.concurrent.TimeUnit

internal const val googleOauthAccessTokenEnvVar = "GOOGLE_OAUTH_ACCESS_TOKEN"
internal const val cloudsdkAuthAccessTokenEnvVar = "CLOUDSDK_AUTH_ACCESS_TOKEN"
private const val childTerminationGraceSeconds = 5L

internal data class ChildEnv(val variables: Map<String, String>, val collisions: Set<String>)

/**
 * Builds the child process's environment: inherited + profile (plain + resolved secret) vars + the
 * brokered token under both `GOOGLE_OAUTH_ACCESS_TOKEN` and `CLOUDSDK_AUTH_ACCESS_TOKEN` (so
 * google-auth libraries and gcloud "just work"). Profile vars win on collision with an inherited
 * value — [ChildEnv.collisions] names which ones, for the caller to warn about.
 */
internal fun buildChildEnv(
    inherited: Map<String, String>,
    profileEnv: Map<String, String>,
    accessToken: String,
): ChildEnv {
  val collisions = profileEnv.keys.intersect(inherited.keys)
  val variables =
      inherited +
          profileEnv +
          mapOf(
              googleOauthAccessTokenEnvVar to accessToken,
              cloudsdkAuthAccessTokenEnvVar to accessToken,
          )
  return ChildEnv(variables, collisions)
}

class ExecCommand : CliktCommand(name = "exec") {
  override fun help(context: Context) =
      "Run a command with the profile's environment injected. Put -- before the command if it " +
          "takes its own flags, e.g. workload worker exec -p my-profile-1 -- gsutil ls gs://bucket"

  private val profileId by option("--profile", "-p", help = "The profile to run under").required()
  private val command by argument(name = "command").multiple(required = true)

  override fun run() {
    val config = loadConfigOrFail()
    val claim =
        try {
          claimWorkload(config.brokerBaseUrl, config.workerId, config.workerSecret, profileId)
        } catch (e: WorkerApiException) {
          throw PrintMessage(
              tokenClaimErrorMessage(e, profileId),
              statusCode = 1,
              printError = true,
          )
        }

    val secretValues =
        try {
          resolveSecrets(claim.secretEnvVars, claim.accessToken)
        } catch (e: SecretResolutionException) {
          throw PrintMessage(
              e.message ?: "Failed to resolve secrets",
              statusCode = 1,
              printError = true,
          )
        }

    echo("Profile:     ${claim.profileId} (revision ${claim.revision})", err = true)
    echo("Service acct: ${claim.serviceAccount}", err = true)
    echo(
        "Token expires: ${claim.expiresAt} (15-min lifetime; longer-running jobs may outlive it — " +
            "token refresh isn't supported yet)",
        err = true,
    )

    val childEnv = buildChildEnv(System.getenv(), claim.envVars + secretValues, claim.accessToken)
    if (childEnv.collisions.isNotEmpty()) {
      echo(
          "Warning: profile env overrides inherited value for: " +
              childEnv.collisions.sorted().joinToString(),
          err = true,
      )
    }

    val processBuilder =
        ProcessBuilder(command)
            .redirectInput(ProcessBuilder.Redirect.INHERIT)
            .redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
    processBuilder.environment().apply {
      clear()
      putAll(childEnv.variables)
    }

    val process =
        try {
          processBuilder.start()
        } catch (e: java.io.IOException) {
          throw PrintMessage(
              "Failed to start '${command.first()}': ${e.message}",
              statusCode = 1,
              printError = true,
          )
        }

    // The terminal typically delivers Ctrl-C to the whole foreground process group already; this
    // hook covers the case where only this JVM is signaled directly (e.g. a plain `kill`).
    val shutdownHook = Thread { forwardTerminationTo(process) }
    Runtime.getRuntime().addShutdownHook(shutdownHook)
    val exitCode =
        try {
          process.waitFor()
        } finally {
          runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
        }

    throw ProgramResult(exitCode)
  }
}

private fun forwardTerminationTo(process: Process) {
  if (!process.isAlive) return
  process.destroy()
  if (!process.waitFor(childTerminationGraceSeconds, TimeUnit.SECONDS)) {
    process.destroyForcibly()
  }
}
