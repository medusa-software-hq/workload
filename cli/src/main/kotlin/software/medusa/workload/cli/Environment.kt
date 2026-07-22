package software.medusa.workload.cli

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

private const val configDirName = "ms-workload"

/**
 * The base state directory, shared by every environment: `$XDG_CONFIG_HOME/ms-workload` or, when
 * that's unset, `~/.config/ms-workload` (also on macOS). Each [Environment] owns a partitioned
 * subdirectory (or, for [Environment.Local], a caller-supplied path) beneath — or beside — this.
 */
internal fun configBaseDir(
    xdgConfigHome: String? = System.getenv("XDG_CONFIG_HOME"),
    userHome: String = System.getProperty("user.home"),
): Path {
  val base =
      if (!xdgConfigHome.isNullOrBlank()) {
        Path.of(xdgConfigHome)
      } else {
        Path.of(userHome, ".config")
      }
  return base.resolve(configDirName)
}

/** Raised when `WORKLOAD_ENVIRONMENT` (or a `local`-only variable) is set to something unusable. */
class EnvironmentSelectionException(message: String) : Exception(message)

/** [text] wrapped in ANSI dim, but only when stderr is an interactive terminal (else plain). */
internal fun dimmedForStderr(text: String): String =
    if (System.console() != null) "\u001B[2m$text\u001B[22m" else text

/**
 * The closed set of environments a single CLI invocation runs against, selected **once** by the
 * `WORKLOAD_ENVIRONMENT` session property (`AWS_PROFILE`-style — deliberately no per-command flag,
 * a flag would invite mixed-plane command sequences). Absent → [Prod].
 *
 * Each environment is a self-contained bundle of everything a command needs — where its state lives
 * (partitioned, never mixed) and which backend + OAuth client to talk to — so the CLI behaves as N
 * independent instances sharing a binary, not one client of N planes. The prod/staging backend URLs
 * and OAuth client ids are Terraform-computed values kept in sync with `infra/common`'s
 * `environment_config` (the deterministic `api.<subdomain_label>.<domain>` host and the per-project
 * `cli_client_id`) — mirrored here as source constants exactly as the single client id used to be.
 */
sealed interface Environment {
  /**
   * Short lowercase name (`prod`/`staging`/`local`); also the state-subdir name for prod/staging.
   */
  val label: String

  /** This environment's private state directory — config.json, admin.json — never shared. */
  val configDir: Path

  /** The one backend endpoint (admin FleetService + worker plane) for this environment. */
  val apiBaseUrl: String

  /** The Desktop OAuth client id the admin sign-in presents; the API's accepted CLI audience. */
  val oauthClientId: String

  /**
   * The OAuth client secret for [oauthClientId] — baked at publish, or an env override for a local
   * build, or null if neither is present (admin login then reports how to set it). For a Desktop
   * client Google explicitly does not treat this as confidential.
   */
  val oauthClientSecret: String?

  /**
   * The one-line stderr banner a non-prod session prints so a human can't mix planes. Null on prod.
   */
  val marker: String?

  /** The env var a local build sets to supply [oauthClientSecret] when nothing is baked. */
  val oauthClientSecretEnvVar: String

  data object Prod : Environment {
    override val label = "prod"
    override val configDir: Path = configBaseDir().resolve(label)
    override val apiBaseUrl = "https://api.workload-baseline.medusa.software"
    override val oauthClientId =
        "136908422577-jllms7h9l7gopqujps2d8e7jvtrc2qgb.apps.googleusercontent.com"
    override val oauthClientSecretEnvVar = "WORKLOAD_ADMIN_OAUTH_CLIENT_SECRET"
    override val oauthClientSecret: String?
      get() =
          System.getenv(oauthClientSecretEnvVar)?.ifBlank { null }
              ?: BuildConfig.bakedProperty("oauthClientSecret")

    override val marker: String? = null
  }

  data object Staging : Environment {
    override val label = "staging"
    override val configDir: Path = configBaseDir().resolve(label)
    override val apiBaseUrl = "https://api.workload-baseline-staging.medusa.software"
    override val oauthClientId =
        "983402080078-rsidgj3id5cv5nqmt6jkgq81v4kpg1tm.apps.googleusercontent.com"
    override val oauthClientSecretEnvVar = "WORKLOAD_ADMIN_OAUTH_CLIENT_SECRET_STAGING"
    override val oauthClientSecret: String?
      get() =
          System.getenv(oauthClientSecretEnvVar)?.ifBlank { null }
              ?: BuildConfig.bakedProperty("stagingOauthClientSecret")

    override val marker = "[staging]"
  }

  /**
   * A developer's local backend. Requires an explicit config path (a temp dir in practice, keeping
   * hermetic tests parallel-safe) and port. The local backend runs the no-op auth decorator, so its
   * accepted admin audience is irrelevant — [oauthClientId]/[oauthClientSecret] mirror prod's only
   * so a local `admin login` attempt has *something* to present.
   */
  data class Local(override val configDir: Path, val port: Int) : Environment {
    override val label = "local"
    override val apiBaseUrl = "http://127.0.0.1:$port"
    override val oauthClientId = Prod.oauthClientId
    override val oauthClientSecretEnvVar = Prod.oauthClientSecretEnvVar
    override val oauthClientSecret: String?
      get() = Prod.oauthClientSecret

    override val marker = "[local]"
  }

  companion object {
    const val ENV_VAR = "WORKLOAD_ENVIRONMENT"
    const val LOCAL_CONFIG_PATH_ENV = "WORKLOAD_LOCAL_CONFIG_PATH"
    const val LOCAL_PORT_ENV = "WORKLOAD_API_LOCAL_PORT"

    /**
     * Resolve the environment for this invocation — the **single** read of `WORKLOAD_ENVIRONMENT`
     * in the whole CLI (the composition root calls this once and injects the result via the Clikt
     * context). Absent/blank → [Prod]; `local` requires both local variables. An unknown value or a
     * misconfigured `local` raises [EnvironmentSelectionException] for a clean top-level message.
     */
    fun current(
        raw: String? = System.getenv(ENV_VAR),
        localConfigPath: String? = System.getenv(LOCAL_CONFIG_PATH_ENV),
        localPort: String? = System.getenv(LOCAL_PORT_ENV),
    ): Environment =
        when (raw?.trim()?.lowercase()?.ifBlank { null }) {
          null,
          "prod",
          "production" -> Prod
          "staging" -> Staging
          "local" -> local(localConfigPath, localPort)
          else ->
              throw EnvironmentSelectionException(
                  "Unknown $ENV_VAR '$raw'. Valid values: prod (default), staging, local."
              )
        }

    private fun local(localConfigPath: String?, localPort: String?): Local {
      val path =
          localConfigPath?.ifBlank { null }
              ?: throw EnvironmentSelectionException(
                  "$ENV_VAR=local requires $LOCAL_CONFIG_PATH_ENV to be set to a config directory."
              )
      val portText =
          localPort?.ifBlank { null }
              ?: throw EnvironmentSelectionException(
                  "$ENV_VAR=local requires $LOCAL_PORT_ENV to be set to the local backend's port."
              )
      val port =
          portText.toIntOrNull()?.takeIf { it in 1..65535 }
              ?: throw EnvironmentSelectionException(
                  "$LOCAL_PORT_ENV must be a port number (1–65535), got '$portText'."
              )
      return Local(Path.of(path), port)
    }
  }
}

/**
 * One-time migration for pre-M5 installs: the old single-environment CLI kept `config.json` /
 * `admin.json` loose under `~/.config/ms-workload/`. Now that state is partitioned per environment,
 * move any such loose files into the `prod/` subdirectory (the only environment the old CLI could
 * talk to) on first run, logging each move. Idempotent — skips a file already present under
 * `prod/`, and does nothing once the loose files are gone. Removed a release later. Runs regardless
 * of the selected environment; it only ever touches the loose legacy files, which are prod's by
 * definition.
 */
internal fun migrateLegacyConfig(
    base: Path = configBaseDir(),
    log: (String) -> Unit = { System.err.println(it) },
) {
  val legacyNames = listOf("config.json", "admin.json")
  val prodDir = base.resolve(Environment.Prod.label)
  val toMigrate = legacyNames.filter {
    Files.exists(base.resolve(it)) && !Files.exists(prodDir.resolve(it))
  }
  if (toMigrate.isEmpty()) return

  if (!Files.exists(prodDir)) {
    runCatching {
          Files.createDirectories(base)
          Files.createDirectory(
              prodDir,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
          )
        }
        .getOrElse { Files.createDirectories(prodDir) }
  }

  toMigrate.forEach { name ->
    runCatching { Files.move(base.resolve(name), prodDir.resolve(name)) }
        .onSuccess {
          log("Migrated ~/.config/ms-workload/$name into the prod/ subdirectory (per-env state).")
        }
        .onFailure { log("Could not migrate $name into prod/: ${it.message}") }
  }
}
