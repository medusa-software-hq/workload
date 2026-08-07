package software.medusa.workload.runtime

import software.medusa.workload.docker.DockerConnector

/**
 * A **network egress sidecar**: an optional second per-run container, alongside the metadata
 * sidecar (see [MetadataSidecar]'s doc), that gives the workload container a route to a *private*
 * service sitting behind Cloudflare Access — one it has no direct network path to and shouldn't be
 * handed Access credentials for itself.
 *
 * The image (`images/cloudflared-access`) wraps the official `cloudflared` binary in `access tcp`
 * mode: it authenticates to Cloudflare Access once (a service token, if one is configured — see
 * [cloudflaredAccessSidecarEnv]) and exposes a plain TCP listener on `0.0.0.0:$port` that tunnels
 * every connection through to the private hostname it was told to reach. The workload container
 * dials that listener like any other host on its network; it never sees an Access credential or a
 * Cloudflare hostname.
 *
 * Isolation follows [MetadataSidecar]'s structural argument exactly, and deliberately reuses it
 * rather than creating a second network: [startCloudflaredAccessSidecar] attaches to the
 * **existing** per-run bridge network `startMetadataSidecar` already created for this run, so the
 * two sidecars and the workload container all share the one network no other run's containers can
 * route onto.
 */

/** Env var the sidecar reads the private hostname to reach through Cloudflare Access from. */
const val cloudflaredHostnameEnvVar = "CFA_SIDECAR_HOSTNAME"

/**
 * Env var the sidecar reads its listen port from — optional, defaults to
 * [defaultCloudflaredAccessPort].
 */
const val cloudflaredPortEnvVar = "CFA_SIDECAR_PORT"

/**
 * Env var carrying a Cloudflare Access service token's client id, for a private service gated by a
 * service-token policy rather than an interactive login. Optional: a hostname reachable without one
 * (e.g. bypassed for the sidecar's own source, or gated some other way) needs neither this nor
 * [cloudflaredServiceTokenSecretEnvVar].
 */
const val cloudflaredServiceTokenIdEnvVar = "CFA_SIDECAR_SERVICE_TOKEN_ID"

/**
 * Env var carrying the service token's secret half — paired with [cloudflaredServiceTokenIdEnvVar].
 */
const val cloudflaredServiceTokenSecretEnvVar = "CFA_SIDECAR_SERVICE_TOKEN_SECRET"

/** The sidecar's default in-container listen port, used when [cloudflaredPortEnvVar] is unset. */
const val defaultCloudflaredAccessPort = 8080

/**
 * Label marking a container as the cloudflared-access sidecar, distinct from the workload it
 * serves.
 */
const val cloudflaredAccessSidecarLabel = "ms-workload.cloudflared-sidecar"

/**
 * The env (as `KEY=VALUE` strings, for a container-create body) that hands the sidecar everything
 * it needs to reach [hostname] through Cloudflare Access: the target hostname, the port to listen
 * on, and — when [serviceTokenId]/[serviceTokenSecret] are both given — the credential that
 * authenticates the tunnel as a headless client rather than an interactive login. Either both are
 * present or neither is; that pairing is the caller's job to enforce (the run pipeline validates it
 * against the CLI's own flags before this is ever called).
 */
fun cloudflaredAccessSidecarEnv(
    hostname: String,
    port: Int = defaultCloudflaredAccessPort,
    serviceTokenId: String? = null,
    serviceTokenSecret: String? = null,
): List<String> =
    listOfNotNull(
        "$cloudflaredHostnameEnvVar=$hostname",
        "$cloudflaredPortEnvVar=$port",
        serviceTokenId?.let { "$cloudflaredServiceTokenIdEnvVar=$it" },
        serviceTokenSecret?.let { "$cloudflaredServiceTokenSecretEnvVar=$it" },
    )

/**
 * Everything [startCloudflaredAccessSidecar] set up, for wiring the workload container and
 * teardown.
 */
data class CloudflaredAccessSidecar(
    val containerId: String,
    /** `ip:port` the workload container should dial to reach the private service. */
    val address: String,
)

/**
 * Starts the cloudflared-access sidecar container on the **already-existing** [networkName] —
 * unlike [startMetadataSidecar], this doesn't create a network of its own; it's meant to join the
 * one the metadata sidecar already created for this run, so every container in a run shares the
 * single network isolating it from every other run. [image]/[cmd] default to the production sidecar
 * (no override command); tests substitute a lightweight stand-in to exercise this wiring without a
 * real `cloudflared` binary.
 *
 * Returns once the container has an IP on [networkName] — see [awaitNetworkAddress].
 */
suspend fun startCloudflaredAccessSidecar(
    connector: DockerConnector,
    image: String,
    networkName: String,
    env: List<String>,
    labels: Map<String, String>,
    port: Int = defaultCloudflaredAccessPort,
    cmd: List<String>? = null,
): CloudflaredAccessSidecar {
  val created =
      connector.containers.create(
          image = image,
          cmd = cmd,
          env = env,
          labels = labels + (cloudflaredAccessSidecarLabel to "true"),
          autoRemove = false,
          networkMode = networkName,
      )
  try {
    connector.containers.start(created.id)
    val ip = awaitNetworkAddress(connector, created.id, networkName)
    return CloudflaredAccessSidecar(containerId = created.id, address = "$ip:$port")
  } catch (e: Exception) {
    runCatching { connector.containers.remove(created.id, force = true) }
    throw e
  }
}

/**
 * Tears down what [startCloudflaredAccessSidecar] created: force-removes just the sidecar
 * container. Deliberately does **not** remove the network — it doesn't own it,
 * [startMetadataSidecar] does — so callers must stop this sidecar before [stopMetadataSidecar],
 * which removes the network and fails loudly if another container (this one) is still attached to
 * it.
 */
suspend fun stopCloudflaredAccessSidecar(
    connector: DockerConnector,
    sidecar: CloudflaredAccessSidecar,
) {
  connector.containers.remove(sidecar.containerId, force = true)
}

/**
 * The workload container's pointers at the private service: the hostname it asked for (so app code
 * can log/assert what it's actually configured to reach) and the sidecar's `ip:port` to dial
 * instead of the hostname directly — the workload never resolves or connects to the real
 * Cloudflare-fronted host itself.
 */
fun privateServicePointerEnv(hostname: String, address: String): Map<String, String> =
    mapOf(
        "WORKLOAD_PRIVATE_SERVICE_HOST" to hostname,
        "WORKLOAD_PRIVATE_SERVICE_ADDR" to address,
    )
