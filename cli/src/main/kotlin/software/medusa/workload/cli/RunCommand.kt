package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.PrintMessage
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import java.io.IOException
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import software.medusa.workload.docker.DockerConnectionException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig
import software.medusa.workload.docker.DockerConnectorException
import software.medusa.workload.runtime.MetadataEmulator
import software.medusa.workload.runtime.RefreshingTokenCache
import software.medusa.workload.runtime.RunReporter
import software.medusa.workload.runtime.SecretBrokerAuth
import software.medusa.workload.runtime.SecretResolutionException
import software.medusa.workload.runtime.WorkerApiException
import software.medusa.workload.runtime.WorkerClaimResponse
import software.medusa.workload.runtime.brokerIdTokenClaimer
import software.medusa.workload.runtime.brokerTokenClaimer
import software.medusa.workload.runtime.buildMetadataContainerEnv
import software.medusa.workload.runtime.claimWorkload
import software.medusa.workload.runtime.containerLabels
import software.medusa.workload.runtime.containerStopGrace
import software.medusa.workload.runtime.garbageCollectAfterRun
import software.medusa.workload.runtime.hostPrimaryAddress
import software.medusa.workload.runtime.installContainerTeardownHook
import software.medusa.workload.runtime.isTrustedRunPeer
import software.medusa.workload.runtime.metadataPointerEnv
import software.medusa.workload.runtime.pinnedImageRef
import software.medusa.workload.runtime.pullBrokeredImage
import software.medusa.workload.runtime.pullFailureMessage
import software.medusa.workload.runtime.repositoryOf
import software.medusa.workload.runtime.resolveSecrets
import software.medusa.workload.runtime.runContainerToCompletion
import software.medusa.workload.runtime.tokenClaimErrorMessage

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
      auth: SecretBrokerAuth,
      claim: WorkerClaimResponse,
      pinnedRef: String,
      secretValues: Map<String, String>,
  ): Int {
    var hook: Thread? = null
    val primary = hostPrimaryAddress()
    val emulator =
        MetadataEmulator(
            RefreshingTokenCache(brokerTokenClaimer(env.apiBaseUrl, auth, profileId)),
            InetSocketAddress(primary, 0),
            ::isTrustedRunPeer,
            idTokenClaimer = brokerIdTokenClaimer(env.apiBaseUrl, auth, profileId),
        )
    emulator.start()
    val metadataAddress = "${primary.hostAddress}:${emulator.port}"
    echo("Metadata:     http://$metadataAddress (tokens refresh automatically)", err = true)

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
      runCatching { emulator.close() }
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
   * Best-effort teardown on Ctrl-C/SIGTERM: stop the container (SIGTERM, then SIGKILL after the
   * grace) and remove it, plus close the metadata emulator. The JVM exits through this hook rather
   * than unwinding, so the `finally` in `runContainerToCompletion` may never run — this is what
   * actually cleans up on Ctrl-C. Accepted teardown boundary: a `kill -9` of this JVM can still
   * leave a container behind, which `workload ps --reap` clears; the emulator dies with the process
   * regardless.
   */
  private fun stopOnShutdownHook(
      connector: DockerConnector,
      containerId: String,
      emulator: MetadataEmulator,
  ): Thread =
      installContainerTeardownHook(connector, containerId, containerStopGrace) {
        runCatching { emulator.close() }
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
