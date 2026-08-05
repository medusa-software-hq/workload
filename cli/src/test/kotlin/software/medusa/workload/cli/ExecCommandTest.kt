package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import software.medusa.workload.runtime.metadataPointerEnv

class ExecCommandTest {

  @Test
  fun `profile vars win over inherited on collision`() {
    val result =
        buildChildEnvWithMetadata(
            inherited = mapOf("MODE" to "interactive", "PATH" to "/usr/bin"),
            profileEnv = mapOf("MODE" to "batch"),
            pointerEnv = emptyMap(),
        )

    assertEquals("batch", result.variables["MODE"])
    assertEquals("/usr/bin", result.variables["PATH"])
    assertEquals(setOf("MODE"), result.collisions)
  }

  @Test
  fun `no collisions when profile and inherited env don't overlap`() {
    val result =
        buildChildEnvWithMetadata(
            inherited = mapOf("PATH" to "/usr/bin"),
            profileEnv = mapOf("MODE" to "batch"),
            pointerEnv = emptyMap(),
        )

    assertEquals(emptySet(), result.collisions)
  }

  @Test
  fun `the metadata child env carries pointers and profile vars but no token`() {
    val result =
        buildChildEnvWithMetadata(
            inherited = mapOf("PATH" to "/usr/bin"),
            profileEnv = mapOf("MODE" to "batch"),
            pointerEnv = metadataPointerEnv("127.0.0.1:49812"),
        )

    assertEquals("/usr/bin", result.variables["PATH"])
    assertEquals("batch", result.variables["MODE"])
    assertEquals("127.0.0.1:49812", result.variables["GCE_METADATA_HOST"])
    assertEquals("127.0.0.1:49812", result.variables["GCE_METADATA_ROOT"])
    // Beacon's whole point: the credential is not in the child env.
    assertTrue(googleOauthAccessTokenEnvVar !in result.variables)
    assertTrue(cloudsdkAuthAccessTokenEnvVar !in result.variables)
  }

  @Test
  fun `inherited env is preserved for vars the profile doesn't touch`() {
    val result =
        buildChildEnvWithMetadata(
            inherited = mapOf("HOME" to "/home/jakub", "PATH" to "/usr/bin"),
            profileEnv = mapOf("MODE" to "batch"),
            pointerEnv = emptyMap(),
        )

    assertEquals("/home/jakub", result.variables["HOME"])
    assertEquals("/usr/bin", result.variables["PATH"])
    assertEquals("batch", result.variables["MODE"])
  }

  @Test
  fun `spawned process receives the profile env and its exit code is passed through`() {
    val process =
        ProcessBuilder("sh", "-c", "echo \"seen=\$PROFILE_VAR\"; exit 7")
            .redirectErrorStream(true)
            .also { builder ->
              builder.environment().apply {
                clear()
                putAll(
                    buildChildEnvWithMetadata(
                            emptyMap(),
                            mapOf("PROFILE_VAR" to "hello"),
                            metadataPointerEnv("127.0.0.1:49812"),
                        )
                        .variables
                )
              }
            }
            .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    assertTrue(output.contains("seen=hello"))
    assertEquals(7, exitCode)
  }
}
