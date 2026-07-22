package software.medusa.workload.cli

import java.util.Properties

/**
 * Values baked into the published fat jar at build time by the Publish CLI workflow — today just
 * the per-environment OAuth client secrets (for a Desktop client Google explicitly does not treat
 * these as confidential, but we still bake rather than commit them). The backend URLs and OAuth
 * client ids are not baked: they're deterministic, public, Terraform-computed values carried as
 * source constants on [Environment]. A local build bakes nothing, and each [Environment] falls back
 * to its `oauthClientSecretEnvVar` for local development.
 */
object BuildConfig {
  private val baked: Properties =
      Properties().apply {
        BuildConfig::class.java.getResourceAsStream("/workload-admin-build.properties")?.use {
          load(it)
        }
      }

  /** A baked property, or null if absent/blank (a local build with nothing baked in). */
  fun bakedProperty(key: String): String? = baked.getProperty(key)?.ifBlank { null }
}
