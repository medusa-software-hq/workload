package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.IOException
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig
import software.medusa.workload.docker.DockerConnectorException
import software.medusa.workload.docker.LogStream

internal const val workloadProfileLabel = "ms-workload.profile"
internal const val workloadRevisionLabel = "ms-workload.revision"
internal const val workloadWorkerLabel = "ms-workload.worker"

// Grace given to the container on Ctrl-C before the daemon SIGKILLs it.
private val containerStopGrace = 10.seconds

/**
 * The repository part of an image ref — the tag (or an existing digest) stripped off.
 * `us-docker.pkg.dev/p/repo/app:v1` -> `us-docker.pkg.dev/p/repo/app`. A `:` is only a tag
 * separator when it sits in the final path segment; before the last `/` it's a registry port.
 */
internal fun repositoryOf(ref: String): String {
  val trimmed = ref.trim()
  val atIndex = trimmed.indexOf('@')
  val withoutDigest = if (atIndex > 0) trimmed.substring(0, atIndex) else trimmed
  val lastColon = withoutDigest.lastIndexOf(':')
  val lastSlash = withoutDigest.lastIndexOf('/')
  return if (lastColon > lastSlash) withoutDigest.substring(0, lastColon) else withoutDigest
}

/** The registry host of an image ref — the first path segment. */
internal fun registryHostOf(ref: String): String = ref.trim().substringBefore('/')

/**
 * The immutable ref to actually pull and run: the repository addressed by the digest the revision
 * pinned at creation time, so a tag that has since moved can't change what runs here.
 */
internal fun pinnedImageRef(image: ClaimImage): String =
    "${repositoryOf(image.ref)}@${image.digest}"

/**
 * The container's environment: the profile's plain vars + resolved secret values + the brokered
 * token under both names google-auth libraries and gcloud look for. Deliberately does **not**
 * inherit this host's environment — a container starts from its image's env, and leaking the
 * operator's shell into it would be surprising.
 *
 * Returned as `KEY=VALUE` strings for the create body only; nothing here ever reaches a command
 * line, so values can't show up in `ps` on the host.
 */
internal fun buildContainerEnv(
    profileEnv: Map<String, String>,
    accessToken: String,
): List<String> =
    (profileEnv +
            mapOf(
                googleOauthAccessTokenEnvVar to accessToken,
                cloudsdkAuthAccessTokenEnvVar to accessToken,
            ))
        .map { (name, value) -> "$name=$value" }

/**
 * Whether a failed `docker pull`'s output reads like a registry auth problem, as opposed to (say) a
 * missing tag or a network error. Keyword-matched because the CLI's exact wording varies by version
 * and registry; a false positive only costs an extra hint line.
 */
internal fun looksLikeAuthFailure(output: String): Boolean {
  val text = output.lowercase()
  return listOf(
          "unauthorized",
          "authentication required",
          "denied",
          "forbidden",
          "no basic auth credentials",
          "login",
      )
      .any { it in text }
}

/** The message for a failed pull, with the one-time gcloud setup hint when auth looks at fault. */
internal fun pullFailureMessage(pinnedRef: String, exitCode: Int, output: String): String {
  val base = "docker pull $pinnedRef failed (exit $exitCode)."
  if (!looksLikeAuthFailure(output)) {
    return "$base See the output above."
  }
  return "$base This looks like a registry authentication failure.\n" +
      "`workload run` uses this host's own Docker sign-in, which needs a one-time setup:\n" +
      "    gcloud auth configure-docker ${registryHostOf(pinnedRef)} --quiet\n" +
      "Then make sure you're signed in (`gcloud auth login`) and try again."
}

/**
 * create -> start -> stream logs -> wait, returning the container's exit code. The whole post-pull
 * lifecycle goes through the library (no `docker` CLI), which is what stage 1 of the migration
 * ladder is proving out.
 *
 * [onCreated] fires with the container id as soon as it exists, so a caller can arm teardown before
 * the container is started. [cmd] overrides the image's own command — `workload run` leaves it null
 * (the profile's image decides what to run); tests use it to drive a stock image.
 *
 * **Why not AutoRemove.** The obvious shape is `AutoRemove=true` and let the daemon reap the
 * container. It doesn't work here: a short-lived container (`echo` and exit) is reaped before our
 * follow-logs request lands, and the daemon answers `404 No such container` — the run's entire
 * output is lost. Docker's own CLI dodges this by attaching *before* it starts the container; our
 * log stream is a cold Flow whose request is only issued once collection begins, so we can't
 * guarantee that ordering without new connector API. Creating without AutoRemove and removing
 * explicitly in a `finally` is deterministic and leaves nothing behind — at the cost of a lingering
 * container if this process is SIGKILLed, which is inside the accepted teardown boundary.
 */
internal suspend fun runContainerToCompletion(
    connector: DockerConnector,
    image: String,
    env: List<String>,
    labels: Map<String, String>,
    onCreated: (String) -> Unit = {},
    cmd: List<String>? = null,
    onStdout: (ByteArray) -> Unit,
    onStderr: (ByteArray) -> Unit,
): Int {
  val created =
      connector.containers.create(
          image = image,
          cmd = cmd,
          env = env,
          labels = labels,
          autoRemove = false,
      )
  onCreated(created.id)

  try {
    connector.containers.start(created.id)
    return coroutineScope {
      val exit = async { connector.containers.wait(created.id) }

      connector.logs.logs(created.id, follow = true).collect { frame ->
        when (frame.stream) {
          LogStream.STDOUT -> onStdout(frame.bytes)
          LogStream.STDERR -> onStderr(frame.bytes)
        }
      }
      exit.await().statusCode
    }
  } finally {
    // NonCancellable so the container is still reaped when the collector is cancelled.
    withContext(NonCancellable) {
      runCatching { connector.containers.remove(created.id, force = true) }
    }
  }
}

class RunCommand : CliktCommand(name = "run") {
  override fun help(context: Context) =
      "Run a profile's container image with its environment injected. Streams the container's " +
          "output and exits with the container's exit code."

  private val profileId by option("--profile", "-p", help = "The profile to run").required()

  override fun run() {
    val config = loadConfigOrFail()
    requireDockerCli()

    DockerConnector(DockerConnectorConfig.fromEnvironment()).use { connector ->
      requireDaemon(connector)

      val claim =
          try {
            claimWorkload(config.brokerBaseUrl, config.workerId, config.workerSecret, profileId)
          } catch (e: WorkerApiException) {
            throw PrintMessage(
                tokenClaimErrorMessage(e, profileId),
                statusCode = 1,
                printError = true,
            )
          } catch (e: IOException) {
            // An unreachable/misconfigured broker surfaces as a raw transport error; don't let it
            // reach the operator as a stack trace.
            throw PrintMessage(
                "Cannot reach the broker at ${config.brokerBaseUrl}: ${e.javaClass.simpleName}" +
                    (e.message?.let { ": $it" } ?: "") +
                    "\nCheck your network, or re-run 'workload register' if the broker URL changed.",
                statusCode = 1,
                printError = true,
            )
          }

      val image =
          claim.image
              ?: throw PrintMessage(
                  "Profile '$profileId' has no container image, so there's nothing to run. " +
                      "Did you mean 'workload exec -p $profileId -- <command>'?",
                  statusCode = 1,
                  printError = true,
              )

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

      val pinnedRef = pinnedImageRef(image)
      echo("Profile:      ${claim.profileId} (revision ${claim.revision})", err = true)
      echo("Service acct: ${claim.serviceAccount}", err = true)
      echo("Image:        ${image.ref}", err = true)
      echo("Pinned to:    $pinnedRef", err = true)

      pullImage(pinnedRef)

      val exitCode = runContainer(connector, claim, pinnedRef, config.workerId, secretValues)
      throw ProgramResult(exitCode)
    }
  }

  /**
   * Stage 1 of the migration ladder: the pull shells out to the `docker` CLI so it rides this
   * host's existing Docker sign-in (credential helpers), which needs no broker-specific auth. The
   * daemon no-ops fast when the digest is already cached. Everything after this goes through the
   * library.
   */
  private fun pullImage(pinnedRef: String) {
    echo("Pulling $pinnedRef ...", err = true)
    val process =
        try {
          ProcessBuilder("docker", "pull", pinnedRef).redirectErrorStream(true).start()
        } catch (e: IOException) {
          throw PrintMessage(
              "Failed to run 'docker pull': ${e.message}",
              statusCode = 1,
              printError = true,
          )
        }

    // Pass the pull's own progress through as it arrives (on stderr, so it can't be confused with
    // the container's stdout), while keeping a copy to diagnose a failure.
    val output = StringBuilder()
    process.inputStream.bufferedReader().forEachLine { line ->
      echo(line, err = true)
      output.appendLine(line)
    }
    val exitCode = process.waitFor()
    if (exitCode != 0) {
      throw PrintMessage(
          pullFailureMessage(pinnedRef, exitCode, output.toString()),
          statusCode = 1,
          printError = true,
      )
    }
  }

  private fun runContainer(
      connector: DockerConnector,
      claim: WorkerClaimResponse,
      pinnedRef: String,
      workerId: String,
      secretValues: Map<String, String>,
  ): Int {
    var hook: Thread? = null
    return try {
      runBlocking {
        runContainerToCompletion(
            connector = connector,
            image = pinnedRef,
            env = buildContainerEnv(claim.envVars + secretValues, claim.accessToken),
            labels =
                mapOf(
                    workloadProfileLabel to claim.profileId,
                    workloadRevisionLabel to claim.revision.toString(),
                    workloadWorkerLabel to workerId,
                ),
            onCreated = { id -> hook = stopOnShutdownHook(connector, id) },
            onStdout = {
              System.out.write(it)
              System.out.flush()
            },
            onStderr = {
              System.err.write(it)
              System.err.flush()
            },
        )
      }
    } catch (e: DockerConnectorException) {
      throw PrintMessage(
          "Failed to run the container: ${e.message}",
          statusCode = 1,
          printError = true,
      )
    } finally {
      hook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
    }
  }

  /**
   * Best-effort teardown on Ctrl-C/SIGTERM: stop the container (SIGTERM, then SIGKILL after the
   * grace) and remove it. The JVM exits through this hook rather than unwinding, so the `finally`
   * in [runContainerToCompletion] may never run — this is what actually cleans up on Ctrl-C. It's
   * the accepted teardown boundary: a `kill -9` of this JVM leaves the container behind.
   */
  private fun stopOnShutdownHook(connector: DockerConnector, containerId: String): Thread {
    val hook = Thread {
      runCatching {
        runBlocking {
          connector.containers.stop(containerId, containerStopGrace)
          connector.containers.remove(containerId, force = true)
        }
      }
    }
    Runtime.getRuntime().addShutdownHook(hook)
    return hook
  }

  private fun requireDockerCli() {
    val found =
        runCatching {
              ProcessBuilder("docker", "--version")
                  .redirectErrorStream(true)
                  .start()
                  .also { it.inputStream.readBytes() }
                  .waitFor() == 0
            }
            .getOrDefault(false)
    if (!found) {
      throw PrintMessage(
          "The 'docker' CLI isn't on PATH. `workload run` needs it to pull images " +
              "(see the host prerequisites in the CLI README).",
          statusCode = 1,
          printError = true,
      )
    }
  }

  private fun requireDaemon(connector: DockerConnector) {
    try {
      runBlocking { connector.ping() }
    } catch (e: DockerConnectionException) {
      throw PrintMessage(
          e.message ?: "Cannot reach the Docker daemon",
          statusCode = 1,
          printError = true,
      )
    }
  }
}
