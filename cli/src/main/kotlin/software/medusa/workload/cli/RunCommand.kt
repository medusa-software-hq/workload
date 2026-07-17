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
import software.medusa.workload.docker.DockerCredentialException
import software.medusa.workload.docker.LogStream
import software.medusa.workload.docker.PullProgress

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
internal fun pullFailureMessage(pinnedRef: String, reason: String): String {
  val base = "Failed to pull $pinnedRef: $reason"
  if (!looksLikeAuthFailure(reason)) {
    return base
  }
  return "$base\n" +
      "This looks like a registry authentication failure. `workload run` uses this host's own\n" +
      "Docker sign-in (via the credential helper in ~/.docker/config.json), which needs a\n" +
      "one-time setup:\n" +
      "    gcloud auth configure-docker ${registryHostOf(pinnedRef)} --quiet\n" +
      "Then make sure you're signed in (`gcloud auth login`) and try again."
}

/**
 * Renders one progress record as a line, or null to skip it. The daemon emits a record per layer
 * per byte-range; keying on (id, status) collapses that to one line per state change, which reads
 * well both on a terminal and in a log. [seen] carries the dedupe state across a pull.
 */
internal fun renderPullProgress(progress: PullProgress, seen: MutableSet<String>): String? {
  val status = progress.status?.takeIf { it.isNotBlank() } ?: return null
  val key = "${progress.id.orEmpty()}|$status"
  if (!seen.add(key)) return null
  return if (progress.id.isNullOrBlank()) status else "${progress.id}: $status"
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

    // No `docker` CLI preflight any more: stage 2 pulls through the library, so the only binary
    // `workload run` may still invoke is the credential *helper*, and only if config.json names
    // one.
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

      pullImage(connector, pinnedRef)

      val exitCode = runContainer(connector, claim, pinnedRef, config.workerId, secretValues)
      throw ProgramResult(exitCode)
    }
  }

  /**
   * Stage 2 of the migration ladder: the pull goes through the library, no `docker` binary
   * involved. It still rides this host's own Docker sign-in — the connector resolves credentials
   * from `~/.docker/config.json`, running the configured credential *helper* binary (which is a
   * separate program from the `docker` CLI, and stays by design).
   *
   * A cached digest makes this a fast no-op. Progress records are rendered to stderr so they can't
   * be confused with the container's own stdout.
   */
  private fun pullImage(connector: DockerConnector, pinnedRef: String) {
    echo("Pulling $pinnedRef ...", err = true)
    val seen = mutableSetOf<String>()
    try {
      runBlocking {
        connector.images.pull(pinnedRef).collect { progress ->
          renderPullProgress(progress, seen)?.let { echo(it, err = true) }
        }
      }
    } catch (e: DockerCredentialException) {
      // Credential-helper problems are already phrased for a human by the connector.
      throw PrintMessage(e.message ?: "Failed to get registry credentials", 1, printError = true)
    } catch (e: DockerConnectorException) {
      // Covers both shapes of pull failure: an HTTP status (DockerApiException) and the in-stream
      // error record a daemon may send after a 200 (DockerPullException).
      throw PrintMessage(
          pullFailureMessage(pinnedRef, e.message ?: e.toString()),
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
