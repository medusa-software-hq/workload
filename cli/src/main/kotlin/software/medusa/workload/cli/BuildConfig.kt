package software.medusa.workload.cli

import java.util.Properties

/**
 * Values baked into the published fat jar at build time by the Publish CLI workflow: the
 * per-environment OAuth client secrets (for a Desktop client Google explicitly does not treat these
 * as confidential, but we still bake rather than commit them), and the metadata-sidecar image ref
 * `workload run` pulls. The backend URLs and OAuth client ids are not baked: they're deterministic,
 * public, Terraform-computed values carried as source constants on [Environment] — the sidecar
 * image ref *can't* be, since the underlying GCP project id has a build-time-random suffix. A local
 * build bakes nothing; each [Environment] falls back to its `oauthClientSecretEnvVar`, and `run`
 * requires `WORKLOAD_METADATA_SIDECAR_IMAGE` instead.
 */
object BuildConfig {
  private val baked: Properties =
      Properties().apply {
        BuildConfig::class.java.getResourceAsStream("/workload-build.properties")?.use { load(it) }
      }

  /** A baked property, or null if absent/blank (a local build with nothing baked in). */
  fun bakedProperty(key: String): String? = baked.getProperty(key)?.ifBlank { null }
}
