package software.medusa.workload.runtime

import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.delay
import software.medusa.workload.docker.DockerConnector

/**
 * The metadata emulator as a **sidecar container** rather than a process bound to this host (see
 * [MetadataEmulator]'s peer-check doc for why the host-bound path can't offer per-run isolation on
 * modern Docker: a container on a custom network simply cannot reach a host-side listener there at
 * all, and even where it can — the default bridge — every profile's container shares the *same*
 * host address, so nothing stops one tenant's container from also fetching another's token).
 *
 * The fix is structural, not a smarter peer check: give every run its **own** bridge network with
 * **its own** emulator container attached, and nothing else. Two runs get two disjoint networks;
 * Docker never routes between them, so a container on tenant A's network has no path at all to
 * tenant B's sidecar — not a 403, an unreachable host. That is what [startMetadataSidecar] sets up,
 * and what the workload container is then joined to via the same [networkName] (`networkMode` on
 * `containers.create`).
 *
 * The sidecar image is an ordinary container: a small JVM app (`images/metadata-emulator`) that
 * wires this same [MetadataEmulator] class, bound to `0.0.0.0:$MS_SIDECAR_PORT` inside its own
 * network namespace, configured entirely by the env vars below — see [metadataSidecarEnv] (the
 * producer, run-side) and [metadataEmulatorFromEnv] (the consumer, sidecar-side).
 */

/** Env var the sidecar reads the broker's worker-plane base URL from. */
const val sidecarBrokerUrlEnvVar = "MS_SIDECAR_BROKER_URL"

/**
 * Env var the sidecar reads the claiming worker's id from (paired with
 * [sidecarWorkerSecretEnvVar]).
 */
const val sidecarWorkerIdEnvVar = "MS_SIDECAR_WORKER_ID"

/**
 * Env var the sidecar reads the claiming worker's secret from. Never set on the workload container.
 */
const val sidecarWorkerSecretEnvVar = "MS_SIDECAR_WORKER_SECRET"

/** Env var the sidecar reads the profile id to claim tokens for. */
const val sidecarProfileIdEnvVar = "MS_SIDECAR_PROFILE_ID"

/** Env var the sidecar reads its bind port from — optional, defaults to [defaultSidecarPort]. */
const val sidecarPortEnvVar = "MS_SIDECAR_PORT"

/** The sidecar's default in-container listen port, used when [sidecarPortEnvVar] is unset. */
const val defaultSidecarPort = 8080

/**
 * Label marking a container as the sidecar itself, distinct from the workload container it serves.
 */
const val workloadSidecarLabel = "ms-workload.sidecar"

/**
 * The env (as `KEY=VALUE` strings, for a container-create body) that hands the sidecar everything
 * it needs to claim and refresh tokens for [profileId] on its own: the broker it talks to, the
 * worker credential it claims as, and the port to listen on. This is the **only** place the
 * worker's secret credential travels to now that the emulator itself is a container — it stays on
 * the sidecar's own env, never the workload container's (mirroring how the brokered access token
 * itself never lands there either).
 */
fun metadataSidecarEnv(
    apiBaseUrl: String,
    workerId: String,
    workerSecret: String,
    profileId: String,
    port: Int = defaultSidecarPort,
): List<String> =
    listOf(
        "$sidecarBrokerUrlEnvVar=$apiBaseUrl",
        "$sidecarWorkerIdEnvVar=$workerId",
        "$sidecarWorkerSecretEnvVar=$workerSecret",
        "$sidecarProfileIdEnvVar=$profileId",
        "$sidecarPortEnvVar=$port",
    )

/** Raised by [metadataEmulatorFromEnv] when a required sidecar env var is absent. */
class SidecarConfigException(message: String) : Exception(message)

/** The sidecar's own config, parsed from its process env — see [metadataEmulatorFromEnv]. */
internal data class SidecarConfig(
    val apiBaseUrl: String,
    val workerId: String,
    val workerSecret: String,
    val profileId: String,
    val port: Int,
)

internal fun parseSidecarConfig(env: Map<String, String>): SidecarConfig {
  fun required(name: String): String =
      env[name]?.ifBlank { null } ?: throw SidecarConfigException("$name is required but unset")

  // 0 is a legal value (bind an OS-chosen ephemeral port) — the sidecar app doesn't rely on it (its
  // port is well-known so the run pipeline can compute the address before ever inspecting the
  // container), but tests do, to avoid fixed-port collisions between parallel runs.
  val port =
      env[sidecarPortEnvVar]
          ?.ifBlank { null }
          ?.let {
            it.toIntOrNull()?.takeIf { p -> p in 0..65535 }
                ?: throw SidecarConfigException(
                    "$sidecarPortEnvVar must be a port number (0-65535), got '$it'"
                )
          } ?: defaultSidecarPort

  return SidecarConfig(
      apiBaseUrl = required(sidecarBrokerUrlEnvVar),
      workerId = required(sidecarWorkerIdEnvVar),
      workerSecret = required(sidecarWorkerSecretEnvVar),
      profileId = required(sidecarProfileIdEnvVar),
      port = port,
  )
}

/**
 * Builds the sidecar's [MetadataEmulator] from its process env (as [metadataSidecarEnv] wrote it),
 * bound wide open on `0.0.0.0:port` — the container's own network namespace, not this host's, is
 * the isolation boundary now (see the class doc above), so there is no host-address/loopback
 * distinction left to make here. [peerAllowed] defaults to [isTrustedRunPeer] (private-range only)
 * as belt-and-braces: the per-run network the sidecar sits on has exactly one *other* member, the
 * workload container it serves, so that check is never expected to actually reject anything — the
 * real isolation is the disjoint per-run network itself, which admits no other peer to begin with.
 */
fun metadataEmulatorFromEnv(
    env: Map<String, String> = System.getenv(),
    peerAllowed: (InetAddress) -> Boolean = ::isTrustedRunPeer,
): MetadataEmulator {
  val config = parseSidecarConfig(env)
  val auth = SecretBrokerAuth(config.workerId, config.workerSecret)
  return MetadataEmulator(
      RefreshingTokenCache(brokerTokenClaimer(config.apiBaseUrl, auth, config.profileId)),
      InetSocketAddress("0.0.0.0", config.port),
      peerAllowed,
      idTokenClaimer = brokerIdTokenClaimer(config.apiBaseUrl, auth, config.profileId),
  )
}

/** Everything [startMetadataSidecar] set up, for wiring the workload container and for teardown. */
data class MetadataSidecar(
    val containerId: String,
    val networkId: String,
    val networkName: String,
    /** `ip:port` the workload container's `GCE_METADATA_*` pointers should target. */
    val address: String,
)

/**
 * Creates a fresh bridge network named [networkName] and starts the emulator container on it — the
 * per-run isolation unit this file's doc describes. [image]/[cmd] default to the production sidecar
 * (no override command; the image's own entrypoint runs); tests substitute a lightweight stand-in
 * image/cmd to exercise this exact wiring without a real broker or JVM image (the network
 * primitives are what's under test there, not the emulator's own HTTP handling — that's
 * [MetadataEmulatorTest]/[MetadataEmulatorContractTest]'s job).
 *
 * Returns once the container has an IP on [networkName] — polls briefly since the daemon may not
 * have finished attaching the instant `start` returns.
 */
suspend fun startMetadataSidecar(
    connector: DockerConnector,
    image: String,
    networkName: String,
    env: List<String>,
    labels: Map<String, String>,
    port: Int = defaultSidecarPort,
    cmd: List<String>? = null,
): MetadataSidecar {
  val network = connector.networks.create(name = networkName, labels = labels)
  val created =
      try {
        connector.containers.create(
            image = image,
            cmd = cmd,
            env = env,
            labels = labels + (workloadSidecarLabel to "true"),
            autoRemove = false,
            networkMode = networkName,
        )
      } catch (e: Exception) {
        runCatching { connector.networks.remove(network.id) }
        throw e
      }
  try {
    connector.containers.start(created.id)
    val ip = awaitNetworkAddress(connector, created.id, networkName)
    return MetadataSidecar(
        containerId = created.id,
        networkId = network.id,
        networkName = networkName,
        address = "$ip:$port",
    )
  } catch (e: Exception) {
    runCatching { connector.containers.remove(created.id, force = true) }
    runCatching { connector.networks.remove(network.id) }
    throw e
  }
}

/**
 * Polls `containers.inspect` for [containerId]'s address on [network] — the daemon assigns it as
 * part of starting the container, which can lag `start`'s own response by a beat.
 */
private suspend fun awaitNetworkAddress(
    connector: DockerConnector,
    containerId: String,
    network: String,
    attempts: Int = 50,
    delayMillis: Long = 100,
): String {
  repeat(attempts) {
    val ip = connector.containers.inspect(containerId).ipOnNetwork(network)
    if (ip != null) return ip
    delay(delayMillis)
  }
  throw IllegalStateException(
      "metadata sidecar container $containerId never got an address on network $network"
  )
}

/**
 * Tears down what [startMetadataSidecar] created: force-removes the sidecar container, then removes
 * the network (which fails loudly if some other container is still attached — by construction only
 * the sidecar and the workload container ever are, and the caller removes the workload container
 * first). Best-effort is the caller's call, same as the rest of run's teardown; this itself doesn't
 * swallow errors so a stuck removal isn't silently ignored.
 */
suspend fun stopMetadataSidecar(connector: DockerConnector, sidecar: MetadataSidecar) {
  connector.containers.remove(sidecar.containerId, force = true)
  connector.networks.remove(sidecar.networkId)
}
