package software.medusa.workload.systemtest

import java.nio.file.Path

/** A real Google-registry image + the target SA that can pull it, for the `workload run` leg. */
internal data class ContainerFixture(val imageRef: String, val targetServiceAccount: String)

/**
 * The backend a system-test run drives, selected once via `SYSTEM_TEST_TARGET` (default `local`) —
 * mirrors the CLI's own `WORKLOAD_ENVIRONMENT` session-property pattern. Bundles everything a test
 * needs to reach it: the env vars the CLI subprocess is launched with, an authenticated admin
 * client, and (staging only) a real container fixture for the one leg that needs to pull a real
 * image through a real registry.
 */
internal sealed interface Target {
  val label: String
  val cliEnv: Map<String, String>
  val admin: AdminClient
  /** The worker-plane base URL, for the raw-HTTP probes the negative-path legs need (bare 404). */
  val workerApiBaseUrl: String
  /** Non-null only when this target can actually pull a real image (see [ContainerFixture]). */
  val containerFixture: ContainerFixture?

  /**
   * The env additions that isolate one worker's state directory from another's within the same
   * suite run (`Environment.Local`'s own doc: "a temp dir in practice, keeping hermetic tests
   * parallel-safe"). Every CLI invocation that registers or acts as a worker must use a fresh
   * [configDir] — reusing one across workers would clash their `config.json`s.
   */
  fun configEnv(configDir: Path): Map<String, String>

  companion object {
    fun current(): Target =
        when (val name = System.getenv("SYSTEM_TEST_TARGET")?.trim()?.lowercase().orEmpty()) {
          "",
          "local" -> Local.create()
          "staging" -> Staging.fromEnvironment()
          else ->
              error(
                  "Unknown SYSTEM_TEST_TARGET '$name' (expected 'local' or 'staging', default 'local')"
              )
        }
  }

  /** The hermetic in-process backend — see [LocalStack]. No auth, no GCP, no Docker. */
  class Local private constructor(port: Int) : Target {
    override val label = "local"
    override val cliEnv =
        mapOf("WORKLOAD_ENVIRONMENT" to "local", "WORKLOAD_API_LOCAL_PORT" to port.toString())
    override val admin = AdminClient.local(port)
    override val workerApiBaseUrl = "http://127.0.0.1:$port"
    // No Google registry reachable from a hermetic stack — requireValidImageRef rejects anything
    // else server-side by design (it's a real security boundary, not a test limitation), so this
    // leg runs `workload worker exec` in place of `workload worker run` locally. See README.md.
    override val containerFixture: ContainerFixture? = null

    override fun configEnv(configDir: Path) =
        mapOf("WORKLOAD_LOCAL_CONFIG_PATH" to configDir.toString())

    companion object {
      fun create(): Local = Local(LocalStack.port)
    }
  }

  /**
   * A deployed environment (staging in practice — prod is never a system-test target). The CLI
   * talks to it directly (its URL is baked into `Environment.kt`); the admin client needs a real
   * Google-signed ID token for the s2s service principal, the same shape `smoke/smoke.sh` and
   * `integration-test-registry-auth.yml` use (WIF-minted in CI, see the nightly workflow).
   */
  class Staging
  private constructor(idToken: String, override val containerFixture: ContainerFixture?) : Target {
    override val label = "staging"
    override val cliEnv = mapOf("WORKLOAD_ENVIRONMENT" to "staging")
    override val workerApiBaseUrl = "https://api.workload-baseline-staging.medusa.software"
    override val admin = AdminClient.staging(apiBaseUrl = workerApiBaseUrl, idToken = idToken)

    // Staging's config dir is otherwise fixed (`configBaseDir()/staging`, keyed off
    // XDG_CONFIG_HOME/user.home) — overriding XDG_CONFIG_HOME per invocation isolates each
    // registered worker's config.json the same way a fresh WORKLOAD_LOCAL_CONFIG_PATH does locally.
    override fun configEnv(configDir: Path) = mapOf("XDG_CONFIG_HOME" to configDir.toString())

    companion object {
      fun fromEnvironment(): Staging {
        val idToken =
            System.getenv("STAGING_ADMIN_ID_TOKEN")
                ?: error(
                    "SYSTEM_TEST_TARGET=staging requires STAGING_ADMIN_ID_TOKEN " +
                        "(a Google-signed ID token for an allowlisted s2s admin principal)"
                )
        val imageRef = System.getenv("STAGING_TEST_IMAGE_REF")?.trim()?.ifBlank { null }
        val fixture = imageRef?.let {
          ContainerFixture(
              imageRef = it,
              targetServiceAccount =
                  System.getenv("STAGING_TEST_TARGET_SA")?.trim()?.ifBlank { null }
                      ?: error(
                          "STAGING_TEST_IMAGE_REF is set but STAGING_TEST_TARGET_SA is not " +
                              "(both come from 'terraform output' in infra/system-test)"
                      ),
          )
        }
        return Staging(idToken, fixture)
      }
    }
  }
}
