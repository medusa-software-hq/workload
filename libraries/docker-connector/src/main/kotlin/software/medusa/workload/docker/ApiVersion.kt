package software.medusa.workload.docker

/**
 * Docker Engine API version negotiation, docker-py-style (see
 * `docker/api/client.py:_retrieve_server_version` in the reference tree).
 *
 * We query the daemon's advertised `ApiVersion` and speak `min(server, [MAX_SUPPORTED])`, but never
 * below [MIN_SUPPORTED] — a daemon older than our floor is a hard error, since it lacks endpoints
 * and fields later stories depend on. The negotiated version prefixes request paths (`/v1.51/...`).
 */
internal object ApiVersion {
  /** Floor. Daemons older than this are rejected. Matches docker-py's historical minimum. */
  const val MIN_SUPPORTED: String = "1.41"

  /**
   * Ceiling: the newest API version whose request/response shapes this library has been written and
   * verified against. Bump it as we validate newer daemons. Capping here (rather than blindly using
   * whatever a future daemon advertises) is docker-py's model — it stops us from addressing an API
   * version whose payloads we might not handle correctly.
   */
  const val MAX_SUPPORTED: String = "1.51"

  /** Returns the version to speak, or throws [DockerProtocolException] if the daemon is too old. */
  fun negotiate(serverApiVersion: String): String {
    if (compare(serverApiVersion, MIN_SUPPORTED) < 0) {
      throw DockerProtocolException(
          "Docker daemon API version $serverApiVersion is older than the minimum supported " +
              "$MIN_SUPPORTED. Upgrade Docker."
      )
    }
    return if (compare(serverApiVersion, MAX_SUPPORTED) < 0) serverApiVersion else MAX_SUPPORTED
  }

  /** Compares dotted `major.minor` versions. Returns <0, 0, >0 like [Comparable.compareTo]. */
  fun compare(a: String, b: String): Int {
    val (aMajor, aMinor) = parse(a)
    val (bMajor, bMinor) = parse(b)
    return if (aMajor != bMajor) aMajor.compareTo(bMajor) else aMinor.compareTo(bMinor)
  }

  private fun parse(v: String): Pair<Int, Int> {
    val parts = v.trim().split('.')
    val major = parts.getOrNull(0)?.toIntOrNull()
    val minor = parts.getOrNull(1)?.toIntOrNull() ?: 0
    if (major == null) {
      throw DockerProtocolException("Unparseable Docker API version: '$v'")
    }
    return major to minor
  }
}
