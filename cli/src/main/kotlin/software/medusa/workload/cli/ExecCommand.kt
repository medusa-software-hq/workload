package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit
import software.medusa.workload.runtime.MetadataEmulator
import software.medusa.workload.runtime.RefreshingTokenCache
import software.medusa.workload.runtime.RunReporter
import software.medusa.workload.runtime.SecretBrokerAuth
import software.medusa.workload.runtime.SecretResolutionException
import software.medusa.workload.runtime.WorkerApiException
import software.medusa.workload.runtime.WorkerClaimResponse
import software.medusa.workload.runtime.brokerIdTokenClaimer
import software.medusa.workload.runtime.brokerTokenClaimer
import software.medusa.workload.runtime.claimWorkload
import software.medusa.workload.runtime.metadataPointerEnv
import software.medusa.workload.runtime.resolveSecrets
import software.medusa.workload.runtime.tokenClaimErrorMessage

// The two env var names google-auth libraries and gcloud read an OAuth access token from. The
// Beacon path deliberately never sets them (the token lives in the metadata emulator, not the
// child's env); kept as the canonical names the token-absence tests assert against.
internal const val googleOauthAccessTokenEnvVar = "GOOGLE_OAUTH_ACCESS_TOKEN"
internal const val cloudsdkAuthAccessTokenEnvVar = "CLOUDSDK_AUTH_ACCESS_TOKEN"
private const val childTerminationGraceSeconds = 5L

internal data class ChildEnv(val variables: Map<String, String>, val collisions: Set<String>)

private fun childEnv(
    inherited: Map<String, String>,
    profileEnv: Map<String, String>,
    injected: Map<String, String>,
): ChildEnv = ChildEnv(inherited + profileEnv + injected, profileEnv.keys.intersect(inherited.keys))

/**
 * Child env for the default (Beacon) path: inherited + profile (plain + resolved secret) vars + the
 * non-secret metadata-emulator **pointer** vars. The brokered token is absent — the child fetches
 * and refreshes it from the emulator, so it never sits in the process's env (or a `ps`-adjacent
 * view). Profile vars win on collision with an inherited value — [ChildEnv.collisions] names which.
 */
internal fun buildChildEnvWithMetadata(
    inherited: Map<String, String>,
    profileEnv: Map<String, String>,
    pointerEnv: Map<String, String>,
): ChildEnv = childEnv(inherited, profileEnv, pointerEnv)

class ExecCommand : CliktCommand(name = "exec") {
  override fun help(context: Context) =
      "Run a command with the profile's environment injected. Put -- before the command if it " +
          "takes its own flags, e.g. workload worker exec -p my-profile-1 -- gsutil ls gs://bucket"

  private val env by requireEnvironment()
  private val profileId by option("--profile", "-p", help = "The profile to run under").required()

  private val command by argument(name = "command").multiple(required = true)

  override fun run() {
    val config = loadConfigOrFail(env)
    val auth = SecretBrokerAuth(config.workerId, config.workerSecret)
    val claim =
        try {
          claimWorkload(env.apiBaseUrl, auth, profileId)
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

    throw ProgramResult(execWithMetadata(auth, claim, secretValues))
  }

  /**
   * Default path: a loopback metadata-server emulator the child fetches (and refreshes) tokens
   * from, exactly as on GCE. No token in the child's env — only the `GCE_METADATA_*` pointers — so
   * a job outliving the 15-minute token keeps working. Loopback exposure to same-user processes on
   * this host is accepted, the same boundary as the profile env values already have via
   * `ps`-adjacent means.
   */
  private fun execWithMetadata(
      auth: SecretBrokerAuth,
      claim: WorkerClaimResponse,
      secretValues: Map<String, String>,
  ): Int {
    val emulator =
        MetadataEmulator(
            RefreshingTokenCache(brokerTokenClaimer(env.apiBaseUrl, auth, profileId)),
            InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            InetAddress::isLoopbackAddress,
            idTokenClaimer = brokerIdTokenClaimer(env.apiBaseUrl, auth, profileId),
        )
    emulator.start()
    echo("Metadata:    http://${emulator.hostPort} (tokens refresh automatically)", err = true)
    return emulator.use {
      val childEnv =
          buildChildEnvWithMetadata(
              System.getenv(),
              claim.envVars + secretValues,
              metadataPointerEnv(emulator.hostPort),
          )
      // Record the run + heartbeat (M6-B1). Best-effort: never blocks or kills the child.
      val reporter =
          RunReporter.start(
              brokerBaseUrl = env.apiBaseUrl,
              auth = auth,
              profileId = claim.profileId,
              revision = claim.revision,
              kind = "exec",
              imageDigest = null,
              warn = { echo(it, err = true) },
          )
      try {
        runChild(childEnv).also { reporter?.reportEnd(it) }
      } finally {
        reporter?.close()
      }
    }
  }

  private fun runChild(childEnv: ChildEnv): Int {
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
    return try {
      process.waitFor()
    } finally {
      runCatching { Runtime.getRuntime().removeShutdownHook(shutdownHook) }
    }
  }
}

private fun forwardTerminationTo(process: Process) {
  if (!process.isAlive) return
  process.destroy()
  if (!process.waitFor(childTerminationGraceSeconds, TimeUnit.SECONDS)) {
    process.destroyForcibly()
  }
}
