package software.medusa.workload.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MetadataSidecarTest {

  @Test
  fun `metadataSidecarEnv carries broker url, worker credential, profile id, and port`() {
    val env =
        metadataSidecarEnv(
            apiBaseUrl = "https://api.example.test",
            workerId = "w1",
            workerSecret = "s3cret",
            profileId = "profile-1",
            port = 9090,
        )
    assertEquals(
        setOf(
            "MS_SIDECAR_BROKER_URL=https://api.example.test",
            "MS_SIDECAR_WORKER_ID=w1",
            "MS_SIDECAR_WORKER_SECRET=s3cret",
            "MS_SIDECAR_PROFILE_ID=profile-1",
            "MS_SIDECAR_PORT=9090",
        ),
        env.toSet(),
    )
  }

  @Test
  fun `metadataSidecarEnv defaults the port when unset`() {
    val env = metadataSidecarEnv("https://api.example.test", "w1", "s3cret", "profile-1")
    assertTrue("MS_SIDECAR_PORT=$defaultSidecarPort" in env)
  }

  private fun envMap(vararg pairs: Pair<String, String>) = pairs.toMap()

  @Test
  fun `parseSidecarConfig reads every required var and defaults the port`() {
    val config =
        parseSidecarConfig(
            envMap(
                sidecarBrokerUrlEnvVar to "https://api.example.test",
                sidecarWorkerIdEnvVar to "w1",
                sidecarWorkerSecretEnvVar to "s3cret",
                sidecarProfileIdEnvVar to "profile-1",
            )
        )
    assertEquals("https://api.example.test", config.apiBaseUrl)
    assertEquals("w1", config.workerId)
    assertEquals("s3cret", config.workerSecret)
    assertEquals("profile-1", config.profileId)
    assertEquals(defaultSidecarPort, config.port)
  }

  @Test
  fun `parseSidecarConfig honors an explicit port`() {
    val config =
        parseSidecarConfig(
            envMap(
                sidecarBrokerUrlEnvVar to "https://api.example.test",
                sidecarWorkerIdEnvVar to "w1",
                sidecarWorkerSecretEnvVar to "s3cret",
                sidecarProfileIdEnvVar to "profile-1",
                sidecarPortEnvVar to "9090",
            )
        )
    assertEquals(9090, config.port)
  }

  @Test
  fun `parseSidecarConfig rejects a non-numeric port`() {
    val e =
        assertFailsWith<SidecarConfigException> {
          parseSidecarConfig(
              envMap(
                  sidecarBrokerUrlEnvVar to "https://api.example.test",
                  sidecarWorkerIdEnvVar to "w1",
                  sidecarWorkerSecretEnvVar to "s3cret",
                  sidecarProfileIdEnvVar to "profile-1",
                  sidecarPortEnvVar to "not-a-port",
              )
          )
        }
    assertTrue(sidecarPortEnvVar in e.message.orEmpty())
  }

  @Test
  fun `parseSidecarConfig fails clearly when a required var is missing`() {
    val e =
        assertFailsWith<SidecarConfigException> {
          parseSidecarConfig(
              envMap(
                  sidecarBrokerUrlEnvVar to "https://api.example.test",
                  sidecarWorkerIdEnvVar to "w1",
                  // sidecarWorkerSecretEnvVar deliberately absent
                  sidecarProfileIdEnvVar to "profile-1",
              )
          )
        }
    assertTrue(sidecarWorkerSecretEnvVar in e.message.orEmpty())
  }

  @Test
  fun `parseSidecarConfig treats a blank required var as missing`() {
    assertFailsWith<SidecarConfigException> {
      parseSidecarConfig(
          envMap(
              sidecarBrokerUrlEnvVar to "  ",
              sidecarWorkerIdEnvVar to "w1",
              sidecarWorkerSecretEnvVar to "s3cret",
              sidecarProfileIdEnvVar to "profile-1",
          )
      )
    }
  }

  @Test
  fun `metadataEmulatorFromEnv builds a bound, closeable emulator from valid env`() {
    val emulator =
        metadataEmulatorFromEnv(
            envMap(
                sidecarBrokerUrlEnvVar to "https://api.example.test",
                sidecarWorkerIdEnvVar to "w1",
                sidecarWorkerSecretEnvVar to "s3cret",
                sidecarProfileIdEnvVar to "profile-1",
                sidecarPortEnvVar to "0",
            )
        )
    try {
      assertTrue(emulator.port > 0, "an ephemeral port (0) should resolve to a real bound port")
    } finally {
      emulator.close()
    }
  }

  @Test
  fun `metadataEmulatorFromEnv surfaces a clear config error rather than an NPE`() {
    assertFailsWith<SidecarConfigException> { metadataEmulatorFromEnv(envMap()) }
  }
}
