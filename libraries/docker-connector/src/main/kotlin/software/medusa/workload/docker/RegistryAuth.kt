package software.medusa.workload.docker

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Docker's credential-helper binaries are all named `docker-credential-<name>`. */
private const val credentialHelperPrefix = "docker-credential-"

/**
 * The username a credential helper returns when the secret is really an identity token rather than
 * a password — the daemon then wants it as `IdentityToken`, not `Password`. `gcloud`'s helper uses
 * this variant. (docker-py: `auth.TOKEN_USERNAME`.)
 */
private const val tokenUsername = "<token>"

private val authJson = Json { ignoreUnknownKeys = true }

/**
 * Credentials for one registry, as the daemon wants them in `X-Registry-Auth`.
 *
 * Field names are capitalized to match docker-py's long-proven wire shape (the daemon's Go JSON
 * decoding is case-insensitive, so this interoperates with the lowercase `AuthConfig` tags).
 */
@Serializable
data class RegistryAuth(
    @SerialName("ServerAddress") val serverAddress: String? = null,
    @SerialName("Username") val username: String? = null,
    @SerialName("Password") val password: String? = null,
    @SerialName("IdentityToken") val identityToken: String? = null,
) {
  /**
   * The `X-Registry-Auth` header value: base64url of the JSON. URL-safe because the header must be
   * ASCII and unpadded/padded both work — docker-py does exactly this.
   */
  fun toHeaderValue(): String =
      Base64.getUrlEncoder().encodeToString(authJson.encodeToString(this).toByteArray())
}

/** Raised when credentials can't be obtained — one clean, actionable failure for the caller. */
class DockerCredentialException(message: String, cause: Throwable? = null) :
    DockerConnectorException(message, cause)

/** What a credential helper prints on stdout for `get`. */
@Serializable
private data class CredentialHelperOutput(
    @SerialName("ServerURL") val serverUrl: String? = null,
    @SerialName("Username") val username: String? = null,
    @SerialName("Secret") val secret: String? = null,
)

/** Runs `docker-credential-<name> get`. Injectable so tests don't need a real helper binary. */
internal fun interface CredentialHelperRunner {
  /**
   * Returns the helper's stdout, or throws [DockerCredentialException] if it can't be run. A
   * non-zero exit means "no credentials" or a real error — [stderr] is folded into the message.
   */
  fun run(helper: String, registry: String): HelperResult
}

internal data class HelperResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Runs a credential-helper program: `<program> get` with the registry on stdin. [program] is
 * normally a bare `docker-credential-<name>` resolved via PATH, but an absolute path works too
 * (which is how this is tested against a real helper script).
 */
internal fun runCredentialHelperProgram(program: String, registry: String): HelperResult {
  val process =
      try {
        ProcessBuilder(program, "get").start()
      } catch (e: Exception) {
        throw DockerCredentialException(
            "Cannot run '$program' — the credential helper configured in " +
                "~/.docker/config.json isn't installed or isn't on PATH.",
            e,
        )
      }
  // The helper reads the registry from stdin and won't answer until stdin is closed.
  //
  // A helper that fails before reading stdin (bad config, not logged in, ...) exits immediately,
  // and on Linux this write then hits a closed pipe and throws "Broken pipe" — masking the real
  // failure behind an IOException. Swallow it: the exit code and stderr read below are what we
  // actually report, and they describe the true problem.
  runCatching { process.outputStream.use { it.write(registry.toByteArray()) } }

  val stdout = process.inputStream.bufferedReader().readText()
  val stderr = process.errorStream.bufferedReader().readText()
  return HelperResult(process.waitFor(), stdout, stderr)
}

/** The default runner: actually executes the helper binary, found on PATH by name. */
internal object ProcessCredentialHelperRunner : CredentialHelperRunner {
  override fun run(helper: String, registry: String): HelperResult =
      runCredentialHelperProgram(credentialHelperPrefix + helper, registry)
}

/**
 * Resolves registry credentials from `~/.docker/config.json` the way Docker itself does, so an
 * image pull rides whatever `gcloud auth configure-docker` (or `docker login`) already set up on
 * this host — no broker-specific auth. Ported from docker-py's `auth.py` + `credentials/store.py`.
 *
 * Resolution order, highest first (docker-py's `resolve_authconfig`):
 * 1. `credHelpers[<registry>]` — a per-registry helper binary.
 * 2. `credsStore` — a global helper binary.
 * 3. `auths[<registry>]` — a static base64 `user:password` entry.
 * 4. anonymous (null) — public images still pull.
 */
class DockerAuthResolver
internal constructor(
    private val configPath: Path,
    private val helperRunner: CredentialHelperRunner,
) {
  constructor(
      configPath: Path = defaultConfigPath()
  ) : this(configPath, ProcessCredentialHelperRunner)

  /** Credentials for [registry] (a bare host like `us-docker.pkg.dev`), or null for anonymous. */
  fun resolve(registry: String): RegistryAuth? {
    val config = readConfig() ?: return null

    val helper = credentialHelperFor(config, registry)
    if (helper != null) {
      // A helper that simply has no entry for this registry falls through to `auths` — only a
      // broken helper is an error.
      fromHelper(helper, registry)?.let {
        return it
      }
    }
    return staticAuth(config, registry)
  }

  private fun credentialHelperFor(config: JsonObject, registry: String): String? {
    val credHelpers = config["credHelpers"]?.jsonObject
    credHelpers?.get(registry)?.jsonPrimitive?.contentOrNull?.let {
      return it
    }
    return config["credsStore"]?.jsonPrimitive?.contentOrNull
  }

  private fun fromHelper(helper: String, registry: String): RegistryAuth? {
    val result = helperRunner.run(helper, registry)
    if (result.exitCode != 0) {
      // Helpers exit non-zero both for "no credentials here" and for real breakage. Docker treats
      // the well-known not-found message as the former; anything else is worth surfacing.
      val combined = (result.stdout + result.stderr).trim()
      if (combined.contains("credentials not found", ignoreCase = true)) return null
      throw DockerCredentialException(
          "The credential helper '$credentialHelperPrefix$helper' failed for $registry " +
              "(exit ${result.exitCode})" +
              if (combined.isBlank()) "." else ": $combined"
      )
    }

    val parsed =
        try {
          authJson.decodeFromString<CredentialHelperOutput>(result.stdout)
        } catch (e: Exception) {
          throw DockerCredentialException(
              "The credential helper '$credentialHelperPrefix$helper' returned output that isn't " +
                  "valid JSON for $registry. Try running " +
                  "'$credentialHelperPrefix$helper get' manually to see what it prints.",
              e,
          )
        }

    // docker-credential-pass returns an empty object rather than exiting non-zero when it has
    // nothing for a registry; treat that as "no credentials" (docker-py does the same).
    if (parsed.username.isNullOrEmpty() && parsed.secret.isNullOrEmpty()) return null

    if (parsed.username == tokenUsername) {
      return RegistryAuth(serverAddress = registry, identityToken = parsed.secret)
    }
    return RegistryAuth(
        serverAddress = registry,
        username = parsed.username,
        password = parsed.secret,
    )
  }

  private fun staticAuth(config: JsonObject, registry: String): RegistryAuth? {
    val entry = config["auths"]?.jsonObject?.get(registry)?.jsonObject ?: return null
    val encoded = entry["auth"]?.jsonPrimitive?.contentOrNull
    if (encoded.isNullOrBlank()) {
      // An entry with no `auth` is what `docker login` leaves behind when a helper owns the
      // credentials; there's nothing usable here.
      return null
    }
    val decoded =
        try {
          String(Base64.getDecoder().decode(encoded))
        } catch (e: IllegalArgumentException) {
          throw DockerCredentialException(
              "The 'auths' entry for $registry in $configPath isn't valid base64.",
              e,
          )
        }
    val separator = decoded.indexOf(':')
    if (separator < 0) {
      throw DockerCredentialException(
          "The 'auths' entry for $registry in $configPath isn't in user:password form."
      )
    }
    return RegistryAuth(
        serverAddress = registry,
        username = decoded.substring(0, separator),
        password = decoded.substring(separator + 1),
    )
  }

  private fun readConfig(): JsonObject? {
    if (!Files.exists(configPath)) return null
    return try {
      authJson.parseToJsonElement(Files.readString(configPath)).jsonObject
    } catch (e: Exception) {
      throw DockerCredentialException("$configPath isn't valid JSON.", e)
    }
  }

  companion object {
    /** `$DOCKER_CONFIG/config.json`, else `~/.docker/config.json` — Docker's own lookup. */
    fun defaultConfigPath(): Path {
      val dockerConfig = System.getenv("DOCKER_CONFIG")
      val base =
          if (!dockerConfig.isNullOrBlank()) Path.of(dockerConfig)
          else Path.of(System.getProperty("user.home"), ".docker")
      return base.resolve("config.json")
    }
  }
}
