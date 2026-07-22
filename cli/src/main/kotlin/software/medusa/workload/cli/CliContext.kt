package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.requireObject
import kotlin.properties.ReadOnlyProperty

/**
 * How an admin call authenticates. Chosen explicitly — never auto-detected — because ambient
 * detection is ambiguous: `gcloud auth application-default login` yields *user* credentials that,
 * in this `google-auth` version, *can* mint ID tokens, so "credentials present → use them" would
 * send a token whose identity (a human user) matches neither the admin plane's human-audience check
 * nor its service-account allowlist, and the API rejects it with a 401. Selecting the method
 * removes the guesswork.
 */
enum class AdminAuthMethod {
  /** Google Sign-In — the cached human browser session (`workload admin login`). */
  GSI,
  /**
   * Service account — ambient credentials: ADC on a dev box, Workload Identity Federation in CI.
   */
  SA;

  companion object {
    const val ENV_VAR = "WORKLOAD_AUTH_METHOD"

    /**
     * Parses [ENV_VAR]: `gsi`/`sa` (case-insensitive), null when unset/blank (the caller defaults
     * to [GSI]), a clean [IllegalArgumentException] on any other value.
     */
    fun fromEnv(raw: String?): AdminAuthMethod? =
        when (raw?.trim()?.lowercase()) {
          null,
          "" -> null
          "gsi" -> GSI
          "sa" -> SA
          else ->
              throw IllegalArgumentException(
                  "$ENV_VAR must be 'gsi' (Google Sign-In) or 'sa' (service account), was '$raw'."
              )
        }
  }
}

/**
 * Process-wide CLI state injected at the Clikt root context: the read-once [environment], the
 * default admin auth method from `$WORKLOAD_AUTH_METHOD` (null = [AdminAuthMethod.GSI]), and the
 * global `--verbose` flag. Sits *above* the per-group `Environment` object, so the existing
 * `requireObject<Environment>()` sites are untouched and this is reached with `findObject`.
 *
 * [verbose] is set by the root command before any subcommand runs.
 */
class CliContext(
    val environment: Environment,
    val authMethodDefault: AdminAuthMethod?,
) {
  var verbose: Boolean = false
}

/**
 * Reads the resolved [Environment] from the root [CliContext] — a drop-in for the former
 * `requireObject<Environment>()`. Clikt's context `obj` is single-keyed, so the [Environment] and
 * the [CliContext] can't coexist as separate objects; the single object is the [CliContext] and
 * every command reaches the environment through it.
 */
fun CliktCommand.requireEnvironment(): ReadOnlyProperty<CliktCommand, Environment> {
  val delegate = requireObject<CliContext>()
  return ReadOnlyProperty { thisRef, property -> delegate.getValue(thisRef, property).environment }
}
