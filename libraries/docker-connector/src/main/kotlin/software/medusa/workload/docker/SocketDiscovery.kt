package software.medusa.workload.docker

import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val discoveryJson = Json { ignoreUnknownKeys = true }

private const val unixScheme = "unix://"

/**
 * The subset of `~/.docker/contexts/meta/<id>/meta.json` we read. Docker's own format; small and
 * stable, which is the whole reason we parse it ourselves instead of shelling out to `docker
 * context inspect` (Drydock §B1 — the product must not need the CLI on PATH).
 */
@Serializable
private data class ContextMeta(
    @SerialName("Name") val name: String? = null,
    @SerialName("Endpoints") val endpoints: Map<String, ContextEndpoint> = emptyMap(),
)

@Serializable private data class ContextEndpoint(@SerialName("Host") val host: String? = null)

/** Where a socket path came from — surfaced in errors so a wrong daemon is diagnosable. */
enum class SocketSource {
  DOCKER_HOST,
  DOCKER_CONTEXT,
  WELL_KNOWN_PATH,
  DEFAULT,
}

/** A discovered socket, and how we found it. */
data class DiscoveredSocket(val path: String, val source: SocketSource, val detail: String? = null)

/**
 * The filesystem/environment facts discovery needs. Injected so the whole matrix (stock Ubuntu,
 * rootless, Docker Desktop, Colima…) is testable on any machine without creating real sockets.
 */
interface DiscoveryHost {
  fun env(name: String): String?

  fun homeDir(): String

  fun exists(path: String): Boolean

  fun readFile(path: String): String?

  fun listDirectories(path: String): List<String>
}

/** The real host. */
internal object SystemDiscoveryHost : DiscoveryHost {
  override fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

  override fun homeDir(): String = System.getProperty("user.home").orEmpty()

  override fun exists(path: String): Boolean =
      java.nio.file.Files.exists(java.nio.file.Path.of(path))

  override fun readFile(path: String): String? =
      runCatching { java.nio.file.Files.readString(java.nio.file.Path.of(path)) }.getOrNull()

  override fun listDirectories(path: String): List<String> =
      runCatching {
            java.nio.file.Files.list(java.nio.file.Path.of(path)).use { stream ->
              stream.filter { java.nio.file.Files.isDirectory(it) }.map { it.toString() }.toList()
            }
          }
          .getOrDefault(emptyList())
}

/**
 * Finds the daemon socket the way Docker itself decides, without needing the `docker` binary:
 * 1. **`DOCKER_HOST`** — an explicit override always wins.
 * 2. **The active `docker context`** — `DOCKER_CONTEXT`, else `currentContext` from
 *    `~/.docker/config.json`, resolved through `~/.docker/contexts/meta/<sha256(name)>/meta.json`.
 *    This is what makes Docker Desktop and Colima work: they don't set `DOCKER_HOST`, they point a
 *    context at a socket under `$HOME`.
 * 3. **Well-known paths**, first that exists — Ubuntu's `/var/run/docker.sock` first (Ubuntu-first
 *    stays the priority), then rootless, then the macOS installers.
 * 4. Otherwise the Ubuntu default, so the "can't reach the daemon" error names something sensible.
 */
object DockerSocketDiscovery {

  /** Well-known socket paths, in priority order. `{home}` is expanded to the user's home. */
  internal fun wellKnownPaths(host: DiscoveryHost): List<String> {
    val home = host.homeDir()
    val xdgRuntimeDir = host.env("XDG_RUNTIME_DIR")
    return buildList {
      // Ubuntu-first: the stock daemon is the platform we test against.
      add("/var/run/docker.sock")
      // Rootless Docker on Linux.
      if (xdgRuntimeDir != null) add("$xdgRuntimeDir/docker.sock")
      // Docker Desktop (macOS, and Linux since it moved under $HOME).
      add("$home/.docker/run/docker.sock")
      // Colima and Lima — common macOS alternatives to Docker Desktop.
      add("$home/.colima/default/docker.sock")
      add("$home/.lima/default/sock/docker.sock")
    }
  }

  /** Docker names each context directory after the SHA-256 of the context name. */
  internal fun contextDirectoryId(contextName: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(contextName.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
  }

  /** The socket for the active context, or null if there isn't one (or it isn't a unix socket). */
  internal fun fromContext(host: DiscoveryHost): DiscoveredSocket? {
    val home = host.homeDir()
    val contextName =
        host.env("DOCKER_CONTEXT")
            ?: currentContextFromConfig(host, "$home/.docker/config.json")
            ?: return null
    // "default" is Docker's built-in context; it has no meta.json and means "the well-known path".
    if (contextName == "default") return null

    val metaPath = "$home/.docker/contexts/meta/${contextDirectoryId(contextName)}/meta.json"
    val meta =
        host.readFile(metaPath)?.let {
          runCatching { discoveryJson.decodeFromString<ContextMeta>(it) }.getOrNull()
        } ?: return null

    val dockerHost = meta.endpoints["docker"]?.host?.takeIf { it.isNotBlank() } ?: return null
    if (!dockerHost.startsWith(unixScheme)) {
      // A tcp:// or ssh:// context is a real configuration we can't serve yet — say so rather than
      // silently using some other daemon, which would run the workload in the wrong place.
      throw DockerConnectionException(
          "The active docker context '$contextName' points at '$dockerHost', but only unix:// " +
              "sockets are supported. Switch context (docker context use default), or set " +
              "DOCKER_HOST to a unix:// socket."
      )
    }
    return DiscoveredSocket(
        path = dockerHost.removePrefix(unixScheme),
        source = SocketSource.DOCKER_CONTEXT,
        detail = "docker context '$contextName'",
    )
  }

  private fun currentContextFromConfig(host: DiscoveryHost, configPath: String): String? {
    val text = host.readFile(configPath) ?: return null
    val obj =
        runCatching { discoveryJson.parseToJsonElement(text) }
            .getOrNull()
            ?.let { runCatching { it as? kotlinx.serialization.json.JsonObject }.getOrNull() }
            ?: return null
    val current = obj["currentContext"] as? kotlinx.serialization.json.JsonPrimitive ?: return null
    return current.content.takeIf { it.isNotBlank() }
  }

  /** Runs the full order and reports what it found. */
  fun discover(host: DiscoveryHost = SystemDiscoveryHost): DiscoveredSocket {
    host.env("DOCKER_HOST")?.let { dockerHost ->
      if (!dockerHost.startsWith(unixScheme)) {
        throw DockerConnectionException(
            "DOCKER_HOST='$dockerHost' is not supported: only unix:// sockets are supported " +
                "(tcp:// and ssh:// are not implemented)."
        )
      }
      return DiscoveredSocket(
          path = dockerHost.removePrefix(unixScheme),
          source = SocketSource.DOCKER_HOST,
          detail = "DOCKER_HOST",
      )
    }

    fromContext(host)?.let {
      return it
    }

    wellKnownPaths(host)
        .firstOrNull { host.exists(it) }
        ?.let {
          return DiscoveredSocket(it, SocketSource.WELL_KNOWN_PATH)
        }

    return DiscoveredSocket(
        DockerConnectorConfig.DEFAULT_SOCKET_PATH,
        SocketSource.DEFAULT,
        detail = "no socket found; falling back to the default",
    )
  }
}
