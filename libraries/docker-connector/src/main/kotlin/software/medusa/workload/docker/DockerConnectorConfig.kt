package software.medusa.workload.docker

/**
 * How the connector finds the daemon socket. Ubuntu-first: the default is the plain
 * `/var/run/docker.sock`; `DOCKER_HOST` is honored only for the `unix://` scheme (tcp/ssh and
 * `docker context` discovery are out of story 01's scope — see the design doc's Ubuntu-first and
 * Transport sections; macOS discovery is story 08).
 */
class DockerConnectorConfig(
    val socketPath: String = DEFAULT_SOCKET_PATH,
) {
  init {
    require(socketPath.isNotBlank()) { "socketPath must not be blank" }
  }

  companion object {
    const val DEFAULT_SOCKET_PATH: String = "/var/run/docker.sock"

    private const val UNIX_SCHEME = "unix://"

    /**
     * Builds config from the environment: `DOCKER_HOST` if it's a `unix://` URL, else the default
     * socket. A non-`unix://` `DOCKER_HOST` is a hard, actionable error rather than a silent
     * fallback — a user who set `tcp://`/`ssh://` should be told it isn't supported yet, not have
     * it quietly ignored.
     */
    fun fromEnvironment(env: (String) -> String? = System::getenv): DockerConnectorConfig {
      val dockerHost = env("DOCKER_HOST")?.takeIf { it.isNotBlank() }
      if (dockerHost == null) {
        return DockerConnectorConfig()
      }
      if (!dockerHost.startsWith(UNIX_SCHEME)) {
        throw DockerConnectionException(
            "DOCKER_HOST='$dockerHost' is not supported: only unix:// sockets are supported " +
                "(tcp/ssh and docker-context discovery are not implemented yet)."
        )
      }
      return DockerConnectorConfig(dockerHost.removePrefix(UNIX_SCHEME))
    }
  }
}
