package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.IOException
import java.net.InetSocketAddress
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
import software.medusa.workload.docker.PullProgress
import software.medusa.workload.docker.RegistryAuth

internal const val workloadProfileLabel = "ms-workload.profile"
internal const val workloadRevisionLabel = "ms-workload.revision"
internal const val workloadWorkerLabel = "ms-workload.worker"

// Marks a per-run bridge network as workload-owned, so `workload ps --reap` can sweep any orphaned
// by a hard-killed CLI (M4-B2). `run` doesn't create these today — the metadata emulator runs on
// the host, not a per-run network (see runContainerWithMetadata) — but the label + reap stand ready
// for the future sidecar-container variant that will. Presence == "workload owns it".
internal const val workloadNetworkLabel = "ms-workload.network"

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
 * The username Google's registries expect when the password is an OAuth access token — the same
 * convention `gcloud auth configure-docker`'s helper uses under the hood.
 */
internal const val brokeredRegistryUsername = "oauth2accesstoken"

/**
 * Whether [host] is a Google-operated container registry. Mirrors the backend's
 * `isGoogleRegistryHost` (which rejects anything else at profile create/update).
 *
 * **A security boundary.** The brokered token is a live credential for the profile's target service
 * account; sending it to a host that isn't Google's would hand that credential away. The backend
 * won't accept a non-Google image ref, so this is belt-and-braces for a profile stored before that
 * rule existed — such an image simply pulls anonymously rather than leaking the token.
 */
internal fun isGoogleRegistryHost(host: String): Boolean {
  val normalized = host.lowercase()
  return normalized == "gcr.io" || normalized.endsWith(".gcr.io") || normalized.endsWith(".pkg.dev")
}

/**
 * The credentials to pull [pinnedRef] with: the brokered token, but **only** for a Google registry.
 * Null means "pull anonymously" — never "fall back to this host's Docker sign-in".
 *
 * One token, two uses: the same access token authenticates the registry pull and the workload's own
 * GCP access inside the container, so IAM stays coherent — it's one identity end to end.
 */
internal fun brokeredRegistryAuth(pinnedRef: String, accessToken: String): RegistryAuth? {
  val host = registryHostOf(pinnedRef)
  if (!isGoogleRegistryHost(host)) return null
  return RegistryAuth(
      serverAddress = host,
      username = brokeredRegistryUsername,
      password = accessToken,
  )
}

/**
 * The immutable ref to actually pull and run: the repository addressed by the digest the revision
 * pinned at creation time, so a tag that has since moved can't change what runs here.
 */
internal fun pinnedImageRef(image: ClaimImage): String =
    "${repositoryOf(image.ref)}@${image.digest}"

/**
 * The container's environment for the default (Beacon) path: the profile's plain vars + resolved
 * secret values + the non-secret metadata-emulator **pointer** vars. The brokered token is
 * deliberately **absent** — the workload fetches (and refreshes) it from the emulator at
 * [pointerEnv]'s address, so `docker inspect` shows a pointer, not a credential. Deliberately does
 * **not** inherit this host's environment.
 *
 * Returned as `KEY=VALUE` strings for the create body only; nothing here ever reaches a command
 * line, so values can't show up in `ps` on the host.
 */
internal fun buildMetadataContainerEnv(
    profileEnv: Map<String, String>,
    pointerEnv: Map<String, String>,
): List<String> = (profileEnv + pointerEnv).map { (name, value) -> "$name=$value" }

/**
 * The container's environment for the legacy `--static-token` path: the profile vars + the brokered
 * token injected under both names google-auth libraries and gcloud look for. One static 15-minute
 * token in the env, no refresh — the pre-Beacon behavior, kept for one release for debugging.
 * Deliberately does **not** inherit this host's environment.
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

/**
 * The message for a failed pull. An auth failure now means the *profile's* target service account
 * can't read the repository — nothing about this host's own sign-in, which `workload worker run` no
 * longer uses. Deliberately does **not** suggest `gcloud auth configure-docker`: falling back to
 * ambient developer credentials would mask a broken opt-in grant and make the profile look fine on
 * the one machine that happens to be logged in.
 */
internal fun pullFailureMessage(pinnedRef: String, serviceAccount: String, reason: String): String {
  val base = "Failed to pull $pinnedRef: $reason"
  if (!looksLikeAuthFailure(reason)) {
    return base
  }
  return "$base\n" +
      "The pull authenticated as the profile's target service account ($serviceAccount), which\n" +
      "appears to lack read access to this repository. An admin needs to grant it\n" +
      "roles/artifactregistry.reader — via the workload-impersonation module's\n" +
      "artifact_repository_id input — and then re-verify the profile.\n" +
      "(`workload worker run` deliberately does not fall back to this machine's own Docker login.)"
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
 * the container is started. [cmd] overrides the image's own command — `workload worker run` leaves
 * it null (the profile's image decides what to run); tests use it to drive a stock image.
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
    onCreated: suspend (String) -> Unit = {},
    cmd: List<String>? = null,
    extraHosts: List<String> = emptyList(),
    networkMode: String? = null,
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
          extraHosts = extraHosts,
          networkMode = networkMode,
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

  private val staticToken by
      option(
              "--static-token",
              help =
                  "Deprecated: inject one static 15-minute token into the container env instead of " +
                      "running the refreshing metadata server. Removed next release.",
          )
          .flag()

  override fun run() {
    val config = loadConfigOrFail()

    // No `docker` CLI preflight any more: stage 2 pulls through the library, so the only binary
    // `workload worker run` may still invoke is the credential *helper*, and only if config.json
    // names
    // one.
    DockerConnector(DockerConnectorConfig.fromEnvironment()).use { connector ->
      requireDaemon(connector)

      val claim =
          try {
            claimWorkload(BuildConfig.apiBaseUrl, config.workerId, config.workerSecret, profileId)
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
                "Cannot reach the broker at ${BuildConfig.apiBaseUrl}: ${e.javaClass.simpleName}" +
                    (e.message?.let { ": $it" } ?: "") +
                    "\nCheck your network, or re-run 'workload worker register' if the broker URL changed.",
                statusCode = 1,
                printError = true,
            )
          }

      val image =
          claim.image
              ?: throw PrintMessage(
                  "Profile '$profileId' has no container image, so there's nothing to run. " +
                      "Did you mean 'workload worker exec -p $profileId -- <command>'?",
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

      pullImage(connector, pinnedRef, claim)

      val exitCode = runContainer(connector, config, claim, pinnedRef, secretValues)
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
  private fun pullImage(connector: DockerConnector, pinnedRef: String, claim: WorkerClaimResponse) {
    echo("Pulling $pinnedRef ...", err = true)
    val seen = mutableSetOf<String>()
    // Stage 3: authenticate with the brokered token, not this host's Docker sign-in. Passing an
    // explicit authConfig also stops the connector consulting ~/.docker/config.json at all, so a
    // fresh machine needs Docker and nothing else — no gcloud, no docker login.
    val auth = brokeredRegistryAuth(pinnedRef, claim.accessToken)
    try {
      runBlocking {
        connector.images.pull(pinnedRef, auth).collect { progress ->
          renderPullProgress(progress, seen)?.let { echo(it, err = true) }
        }
      }
    } catch (e: DockerConnectorException) {
      // Covers both shapes of pull failure: an HTTP status (DockerApiException) and the in-stream
      // error record a daemon may send after a 200 (DockerPullException). No fallback to credential
      // helpers on a 401/403 — see pullFailureMessage.
      throw PrintMessage(
          pullFailureMessage(pinnedRef, claim.serviceAccount, e.message ?: e.toString()),
          statusCode = 1,
          printError = true,
      )
    }
  }

  private fun runContainer(
      connector: DockerConnector,
      config: WorkloadConfig,
      claim: WorkerClaimResponse,
      pinnedRef: String,
      secretValues: Map<String, String>,
  ): Int =
      if (staticToken) {
        runContainerStaticToken(connector, claim, pinnedRef, config.workerId, secretValues)
      } else {
        runContainerWithMetadata(connector, config, claim, pinnedRef, secretValues)
      }

  private fun containerLabels(claim: WorkerClaimResponse, workerId: String): Map<String, String> =
      mapOf(
          workloadProfileLabel to claim.profileId,
          workloadRevisionLabel to claim.revision.toString(),
          workloadWorkerLabel to workerId,
      )

  /**
   * The Beacon path (default): an in-CLI metadata-server emulator, reached by the container exactly
   * as the GCE metadata server would be. The container fetches and refreshes brokered tokens on
   * demand — no access token in its env, only the non-secret `GCE_METADATA_*` pointers — so jobs
   * longer than the token's 15-minute lifetime keep working.
   *
   * **Why the host primary IP, not a per-run network gateway (as the M4 design first proposed).**
   * Verified on the Ubuntu VM (Docker 29): a freshly-created bridge's gateway IP is not assigned to
   * any host interface (unbindable), and — decisively — a container on a *custom* network cannot
   * reach a host-side listener at all under modern Docker's host-access hardening. The one path
   * that works is the default bridge reaching the host's own primary IP (Docker source-NATs it
   * there). So the emulator binds that address; the peer check ([isTrustedRunPeer]) admits the
   * private-range source, and the `Metadata-Flavor` header + random port complete the guard.
   * Restoring the design's per-run-network, per-container isolation needs the sidecar-container
   * variant — deferred; the B2 network API stands ready for it.
   */
  private fun runContainerWithMetadata(
      connector: DockerConnector,
      config: WorkloadConfig,
      claim: WorkerClaimResponse,
      pinnedRef: String,
      secretValues: Map<String, String>,
  ): Int {
    var hook: Thread? = null
    val primary = hostPrimaryAddress()
    val emulator =
        MetadataEmulator(
            RefreshingTokenCache(brokerTokenClaimer(config, profileId)),
            InetSocketAddress(primary, 0),
            ::isTrustedRunPeer,
            idTokenClaimer = brokerIdTokenClaimer(config, profileId),
        )
    emulator.start()
    val metadataAddress = "${primary.hostAddress}:${emulator.port}"
    echo("Metadata:     http://$metadataAddress (tokens refresh automatically)", err = true)

    return try {
      runBlocking {
        runContainerToCompletion(
            connector = connector,
            image = pinnedRef,
            env =
                buildMetadataContainerEnv(
                    claim.envVars + secretValues,
                    metadataPointerEnv(metadataAddress),
                ),
            labels = containerLabels(claim, config.workerId),
            extraHosts = listOf("metadata.google.internal:${primary.hostAddress}"),
            onCreated = { id -> hook = stopOnShutdownHook(connector, id, emulator) },
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
      runCatching { emulator.close() }
    }
  }

  /** Legacy `--static-token` path: one static 15-minute token in the container env, no refresh. */
  private fun runContainerStaticToken(
      connector: DockerConnector,
      claim: WorkerClaimResponse,
      pinnedRef: String,
      workerId: String,
      secretValues: Map<String, String>,
  ): Int {
    echo(
        "Warning: --static-token injects one 15-minute token and does not refresh; a longer job " +
            "will lose GCP access mid-run. This flag is deprecated and goes away next release.",
        err = true,
    )
    var hook: Thread? = null
    return try {
      runBlocking {
        runContainerToCompletion(
            connector = connector,
            image = pinnedRef,
            env = buildContainerEnv(claim.envVars + secretValues, claim.accessToken),
            labels = containerLabels(claim, workerId),
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

  /**
   * Beacon-path shutdown hook: the container teardown above, plus closing the metadata emulator.
   * Same accepted boundary — a `kill -9` of this JVM can still leave a container behind, which
   * `workload ps --reap` clears; the emulator dies with the process regardless.
   */
  private fun stopOnShutdownHook(
      connector: DockerConnector,
      containerId: String,
      emulator: MetadataEmulator,
  ): Thread {
    val hook = Thread {
      runCatching {
        runBlocking {
          connector.containers.stop(containerId, containerStopGrace)
          connector.containers.remove(containerId, force = true)
        }
      }
      runCatching { emulator.close() }
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
