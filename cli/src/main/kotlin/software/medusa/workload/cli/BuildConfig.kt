package software.medusa.workload.cli

import java.util.Properties

/**
 * Values baked into the published fat jar at build time. The one backend serves **both** the admin
 * FleetService and the worker plane (`/worker/v2/…`), so a single [apiBaseUrl] is the endpoint for
 * the whole CLI — there is no per-worker "broker" to configure. It's baked by the Publish CLI
 * workflow (`-PadminApiBaseUrl`, from the `API_URL` Actions variable — the same URL the SPA builds
 * against); a local build has nothing baked and falls back to the prod URL, overridable via
 * [API_URL_ENV] for local development (the admin plane already worked this way).
 */
object BuildConfig {
  /** Override the API endpoint for local dev, e.g. `WORKLOAD_API_URL=http://localhost:8081`. */
  const val API_URL_ENV = "WORKLOAD_API_URL"

  // Recognized for back-compat with the admin plane's original env var.
  private const val LEGACY_ADMIN_API_URL_ENV = "WORKLOAD_ADMIN_API_URL"

  /** The prod backend URL — the fallback when nothing is baked (a local build). */
  const val DEFAULT_API_BASE_URL = "https://api-s5hue5meiq-ew.a.run.app"

  private val baked: Properties =
      Properties().apply {
        BuildConfig::class.java.getResourceAsStream("/workload-admin-build.properties")?.use {
          load(it)
        }
      }

  /** A baked property, or null if absent/blank (a local build with nothing baked in). */
  fun bakedProperty(key: String): String? = baked.getProperty(key)?.ifBlank { null }

  /** The backend API base URL: env override (dev) > value baked at build > prod default. */
  val apiBaseUrl: String
    get() =
        resolve(
            System.getenv(API_URL_ENV) ?: System.getenv(LEGACY_ADMIN_API_URL_ENV),
            bakedProperty("apiBaseUrl"),
            DEFAULT_API_BASE_URL,
        )!!

  /** env override > value baked at build > default. Blank is treated as absent at every level. */
  internal fun resolve(envValue: String?, bakedValue: String?, default: String?): String? =
      envValue?.ifBlank { null } ?: bakedValue?.ifBlank { null } ?: default
}
