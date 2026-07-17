package software.medusa.workload.docker

/**
 * How the connector finds the daemon socket. Ubuntu-first: `/var/run/docker.sock` is the default
 * and the first well-known path tried, and it's the platform the contract tests run against.
 *
 * [fromEnvironment] runs the full discovery order (`DOCKER_HOST` → the active `docker context` →
 * well-known paths); see [DockerSocketDiscovery]. Discovery deliberately never shells out to the
 * `docker` binary — the product must work with no CLI on PATH.
 */
class DockerConnectorConfig(
    val socketPath: String = DEFAULT_SOCKET_PATH,
    /** How [socketPath] was found. Purely diagnostic; defaults to an explicitly-passed path. */
    val source: SocketSource = SocketSource.DEFAULT,
) {
  init {
    require(socketPath.isNotBlank()) { "socketPath must not be blank" }
  }

  companion object {
    const val DEFAULT_SOCKET_PATH: String = "/var/run/docker.sock"

    /**
     * Discovers the daemon socket from the environment. A non-`unix://` `DOCKER_HOST` (or docker
     * context) is a hard, actionable error rather than a silent fallback — a user who set
     * `tcp://`/`ssh://` should be told it isn't supported, not have their workload quietly run
     * against some other daemon.
     */
    fun fromEnvironment(host: DiscoveryHost = SystemDiscoveryHost): DockerConnectorConfig {
      val discovered = DockerSocketDiscovery.discover(host)
      return DockerConnectorConfig(discovered.path, discovered.source)
    }
  }
}
