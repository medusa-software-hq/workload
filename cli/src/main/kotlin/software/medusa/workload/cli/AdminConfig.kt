package software.medusa.workload.cli

import java.util.Properties

/**
 * OAuth client + API endpoint the `admin` commands use.
 *
 * The client id is public and lives in source. The client secret (which, for a Desktop OAuth
 * client, Google explicitly does not treat as confidential) and the API base URL are baked into the
 * fat jar at build time from a generated resource — the Publish CLI workflow passes them as Gradle
 * properties (`-PadminOauthClientSecret`, `-PadminApiBaseUrl`) sourced from an Actions secret and
 * variable, so neither is committed. A locally built CLI has neither baked in and falls back to env
 * vars / a default, so dev builds still work.
 */
object AdminConfig {
  /** The workload-baseline-desktop OAuth client. Public; safe in source. */
  const val CLIENT_ID = "852264381191-8blq9kgof5o0j65cjjb00peqb144hpen.apps.googleusercontent.com"

  const val CLIENT_SECRET_ENV = "WORKLOAD_ADMIN_OAUTH_CLIENT_SECRET"
  const val API_BASE_URL_ENV = "WORKLOAD_ADMIN_API_URL"

  // The API service's stable Cloud Run URL (same value the SPA builds against). Used when nothing
  // was baked in or overridden — the common case for the published CLI is the baked value.
  private const val DEFAULT_API_BASE_URL = "https://api-s5hue5meiq-ew.a.run.app"

  private val baked: Properties =
      Properties().apply {
        AdminConfig::class.java.getResourceAsStream("/workload-admin-build.properties")?.use {
          load(it)
        }
      }

  /**
   * The OAuth client secret, or null if this build has none baked in and no env override is set —
   * in which case `admin login` can't run and the caller reports how to fix it.
   */
  val clientSecret: String?
    get() = resolve(System.getenv(CLIENT_SECRET_ENV), baked.getProperty("oauthClientSecret"), null)

  /** The admin API base URL: env override, else the baked value, else the default. */
  val apiBaseUrl: String
    get() =
        resolve(
            System.getenv(API_BASE_URL_ENV),
            baked.getProperty("apiBaseUrl"),
            DEFAULT_API_BASE_URL,
        )!!

  /** env override > value baked at build > default. Blank is treated as absent at every level. */
  internal fun resolve(envValue: String?, bakedValue: String?, default: String?): String? =
      envValue?.ifBlank { null } ?: bakedValue?.ifBlank { null } ?: default
}
