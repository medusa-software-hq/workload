package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig
import software.medusa.workload.docker.DockerConnectorException
import software.medusa.workload.runtime.MetadataSidecar
import software.medusa.workload.runtime.RunReporter
import software.medusa.workload.runtime.SecretBrokerAuth
import software.medusa.workload.runtime.SecretResolutionException
import software.medusa.workload.runtime.WorkerApiException
import software.medusa.workload.runtime.WorkerClaimResponse
import software.medusa.workload.runtime.buildMetadataContainerEnv
import software.medusa.workload.runtime.claimWorkload
import software.medusa.workload.runtime.containerLabels
import software.medusa.workload.runtime.containerStopGrace
import software.medusa.workload.runtime.garbageCollectAfterRun
import software.medusa.workload.runtime.installContainerTeardownHook
import software.medusa.workload.runtime.metadataPointerEnv
import software.medusa.workload.runtime.metadataSidecarEnv
import software.medusa.workload.runtime.pinnedImageRef
import software.medusa.workload.runtime.pullBrokeredImage
import software.medusa.workload.runtime.pullFailureMessage
import software.medusa.workload.runtime.renderPullProgress
import software.medusa.workload.runtime.repositoryOf
import software.medusa.workload.runtime.resolveSecrets
import software.medusa.workload.runtime.runContainerToCompletion
import software.medusa.workload.runtime.startMetadataSidecar
import software.medusa.workload.runtime.stopMetadataSidecar
import software.medusa.workload.runtime.tokenClaimErrorMessage
import software.medusa.workload.runtime.workloadNetworkLabel

/**
 * Env var overriding the metadata-sidecar image `workload run` pulls — see
 * [resolveMetadataSidecarImage].
 */
internal const val metadataSidecarImageEnvVar = "WORKLOAD_METADATA_SIDECAR_IMAGE"

class RunCommand : CliktCommand(name = "run") {
  override fun help(context: Context) =
      "Run a profile's container image with its environment injected. Streams the container's " +
          "output and exits with the container's exit code."

  private val env by requireEnvironment()
  private val profileId by option("--profile", "-p", help = "The profile to run").required()

  override fun run() {
    val config = loadConfigOrFail(env)
    val auth = SecretBrokerAuth(config.workerId, config.workerSecret)

    // No `docker` CLI preflight any more: stage 2 pulls through the library, so the only binary
    // `workload worker run` may still invoke is the credential *helper*, and only if config.json
    // names
    // one.
    DockerConnector(DockerConnectorConfig.fromEnvironment()).use { connector ->
      requireDaemon(connector)

      val claim =
          try {
            claimWorkload(env.apiBaseUrl, auth, profileId)
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
                "Cannot reach the broker at ${env.apiBaseUrl}: ${e.javaClass.simpleName}" +
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

      // Best-effort GC (M7-00): the new digest is pulled, so any older revision of this repo is now
      // superseded. Reap our exited containers and sweep the old images before the run proceeds, so
      // disk frees immediately even on a long-lived run. Failures only warn — never block the run.
      runBlocking {
        garbageCollectAfterRun(
            connector,
            env.configDir,
            repositoryOf(image.ref),
            image.digest,
        ) {
          echo(it, err = true)
        }
      }

      val exitCode =
          runContainerWithMetadata(connector, config, auth, claim, pinnedRef, secretValues)
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
    // Stage 3: authenticate with the brokered token, not this host's Docker sign-in. Passing an
    // explicit authConfig also stops the connector consulting ~/.docker/config.json at all, so a
    // fresh machine needs Docker and nothing else — no gcloud, no docker login.
    try {
      runBlocking {
        pullBrokeredImage(connector, pinnedRef, claim.accessToken) { echo(it, err = true) }
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

  /**
   * The Beacon path (default): the metadata emulator, reached by the container exactly as the GCE
   * metadata server would be. The container fetches and refreshes brokered tokens on demand — no
   * access token in its env, only the non-secret `GCE_METADATA_*` pointers — so jobs longer than
   * the token's 15-minute lifetime keep working.
   *
   * **Why a sidecar container, not a host-bound emulator (as `run` used to do).** Verified on the
   * Ubuntu VM (Docker 29): a container on a *custom* network can't reach a host-side listener at
   * all under modern Docker's host-access hardening, and even the one path that did work — the
   * default bridge reaching the host's own primary IP — put every profile's container behind the
   * *same* address, with only a private-range peer check standing between one tenant and another's
   * token. So the emulator now runs as its own container (`images/metadata-emulator`), on a bridge
   * network created fresh for this run and shared with nothing but the workload container it
   * serves. Two runs get two disjoint networks; Docker never routes between them, so cross-tenant
   * reachability isn't merely rejected, there is no path at all. See `MetadataSidecar.kt` for the
   * wiring, and `MetadataSidecarIsolationContractTest` for the proof.
   */
  private fun runContainerWithMetadata(
      connector: DockerConnector,
      config: WorkloadConfig,
      auth: SecretBrokerAuth,
      claim: WorkerClaimResponse,
      pinnedRef: String,
      secretValues: Map<String, String>,
  ): Int {
    val sidecarImage = resolveMetadataSidecarImage()
    val networkName = "ms-workload-run-" + UUID.randomUUID().toString().take(8)
    val labels = containerLabels(claim, config.workerId) + (workloadNetworkLabel to "true")

    pullMetadataSidecarImage(connector, sidecarImage)

    var hook: Thread? = null
    val sidecar = runBlocking {
      startMetadataSidecar(
          connector = connector,
          image = sidecarImage,
          networkName = networkName,
          env = metadataSidecarEnv(env.apiBaseUrl, config.workerId, config.workerSecret, profileId),
          labels = labels,
      )
    }
    echo("Metadata:     http://${sidecar.address} (tokens refresh automatically)", err = true)

    // Record the run + start heartbeating (M6-B1). Best-effort: a broker hiccup here never stops
    // the
    // container — reporter is null / its calls warn, and the workload runs regardless.
    val reporter =
        RunReporter.start(
            brokerBaseUrl = env.apiBaseUrl,
            auth = auth,
            profileId = claim.profileId,
            revision = claim.revision,
            kind = "run",
            imageDigest = claim.image?.digest,
            warn = { echo(it, err = true) },
        )

    return try {
      runBlocking {
            runContainerToCompletion(
                connector = connector,
                image = pinnedRef,
                env =
                    buildMetadataContainerEnv(
                        claim.envVars + secretValues,
                        metadataPointerEnv(sidecar.address),
                    ),
                labels = labels,
                extraHosts =
                    listOf("metadata.google.internal:${sidecar.address.substringBefore(':')}"),
                networkMode = networkName,
                onCreated = { id -> hook = stopOnShutdownHook(connector, id, sidecar) },
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
          .also { reporter?.reportEnd(it) }
    } catch (e: DockerConnectorException) {
      throw PrintMessage(
          "Failed to run the container: ${e.message}",
          statusCode = 1,
          printError = true,
      )
    } finally {
      // Reports a failure-end if the container threw before reporting one, and unregisters the
      // hook.
      reporter?.close()
      hook?.let { runCatching { Runtime.getRuntime().removeShutdownHook(it) } }
      // The workload container is already gone by this point (runContainerToCompletion's own
      // finally removed it), so the sidecar container is the network's last endpoint.
      runCatching { runBlocking { stopMetadataSidecar(connector, sidecar) } }
      // Second GC pass at teardown (M7-00): our container has been removed, so its now-exited
      // predecessor is reapable and this repo's superseded image sweepable. Best-effort.
      runCatching {
        runBlocking {
          garbageCollectAfterRun(
              connector,
              env.configDir,
              repositoryOf(pinnedRef),
              pinnedRef.substringAfterLast('@'),
          ) {
            echo(it, err = true)
          }
        }
      }
    }
  }

  /**
   * Best-effort teardown on Ctrl-C/SIGTERM: stop the workload container (SIGTERM, then SIGKILL
   * after the grace) and remove it, then tear down the sidecar container + its per-run network. The
   * JVM exits through this hook rather than unwinding, so the `finally` in
   * `runContainerWithMetadata` may never run — this is what actually cleans up on Ctrl-C. Accepted
   * teardown boundary: a `kill -9` of this JVM can still leave a container (or the sidecar/network)
   * behind, which `workload ps --reap` clears.
   */
  private fun stopOnShutdownHook(
      connector: DockerConnector,
      containerId: String,
      sidecar: MetadataSidecar,
  ): Thread =
      installContainerTeardownHook(connector, containerId, containerStopGrace) {
        runCatching { runBlocking { stopMetadataSidecar(connector, sidecar) } }
      }

  /**
   * The metadata-sidecar image ref: [metadataSidecarImageEnvVar] if set (local dev, or an operator
   * overriding the published image), else the ref the Publish CLI workflow baked in at build time
   * (see `BuildConfig`). Unlike `Environment`'s backend URLs, this can't be a plain source constant
   * — the underlying Artifact Registry project id carries a build-time-random suffix — so a build
   * with neither set (e.g. compiled straight from source) fails clearly rather than guessing.
   */
  private fun resolveMetadataSidecarImage(): String =
      System.getenv(metadataSidecarImageEnvVar)?.ifBlank { null }
          ?: BuildConfig.bakedProperty("metadataSidecarImage")
          ?: throw PrintMessage(
              "No metadata-sidecar image configured. Set $metadataSidecarImageEnvVar to an image " +
                  "ref this host can pull (see images/metadata-emulator), or use a published CLI " +
                  "build, which bakes one in.",
              statusCode = 1,
              printError = true,
          )

  /**
   * Pulls [image] with this host's own ambient Docker credentials (never the brokered token — the
   * sidecar is what *obtains* that token, so it can't itself be gated behind it). Progress is
   * rendered the same way the workload image's pull is.
   */
  private fun pullMetadataSidecarImage(connector: DockerConnector, image: String) {
    echo("Pulling metadata sidecar $image ...", err = true)
    val seen = mutableSetOf<String>()
    try {
      runBlocking {
        connector.images.pull(image).collect { progress ->
          renderPullProgress(progress, seen)?.let { echo(it, err = true) }
        }
      }
    } catch (e: DockerConnectorException) {
      throw PrintMessage(
          "Failed to pull the metadata-sidecar image ($image): ${e.message}\n" +
              "This image is pulled with this host's own Docker sign-in, not the brokered token " +
              "(it's what obtains that token). Run 'gcloud auth configure-docker' for a private " +
              "registry, or point $metadataSidecarImageEnvVar at one this host can already reach.",
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
