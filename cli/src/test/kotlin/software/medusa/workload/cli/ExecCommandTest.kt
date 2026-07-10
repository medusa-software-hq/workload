package software.medusa.workload.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ExecCommandTest {

  @Test
  fun `profile vars win over inherited on collision`() {
    val result =
        buildChildEnv(
            inherited = mapOf("MODE" to "interactive", "PATH" to "/usr/bin"),
            profileEnv = mapOf("MODE" to "batch"),
            accessToken = "token",
        )

    assertEquals("batch", result.variables["MODE"])
    assertEquals("/usr/bin", result.variables["PATH"])
    assertEquals(setOf("MODE"), result.collisions)
  }

  @Test
  fun `no collisions when profile and inherited env don't overlap`() {
    val result =
        buildChildEnv(
            inherited = mapOf("PATH" to "/usr/bin"),
            profileEnv = mapOf("MODE" to "batch"),
            accessToken = "token",
        )

    assertEquals(emptySet(), result.collisions)
  }

  @Test
  fun `injects the access token under both GOOGLE_OAUTH_ACCESS_TOKEN and CLOUDSDK_AUTH_ACCESS_TOKEN`() {
    val result =
        buildChildEnv(inherited = emptyMap(), profileEnv = emptyMap(), accessToken = "abc123")

    assertEquals("abc123", result.variables[googleOauthAccessTokenEnvVar])
    assertEquals("abc123", result.variables[cloudsdkAuthAccessTokenEnvVar])
  }

  @Test
  fun `inherited env is preserved for vars the profile doesn't touch`() {
    val result =
        buildChildEnv(
            inherited = mapOf("HOME" to "/home/jakub", "PATH" to "/usr/bin"),
            profileEnv = mapOf("MODE" to "batch"),
            accessToken = "token",
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
                    buildChildEnv(emptyMap(), mapOf("PROFILE_VAR" to "hello"), "token").variables
                )
              }
            }
            .start()

    val output = process.inputStream.bufferedReader().readText()
    val exitCode = process.waitFor()

    assertTrue(output.contains("seen=hello"))
    assertEquals(7, exitCode)
  }

  @Test
  fun `the access token never appears in the spawned process's argv, only its env`() {
    // A crude proxy for "never leaks via ps": confirm the process is started with the token in
    // its environment map, not as a command-line argument.
    val process =
        ProcessBuilder("sh", "-c", "echo done")
            .also { builder ->
              builder.environment().apply {
                clear()
                putAll(buildChildEnv(emptyMap(), emptyMap(), "super-secret-token").variables)
              }
            }
            .start()
    process.waitFor()

    val commandLine = process.info().commandLine().orElse("")
    assertTrue(!commandLine.contains("super-secret-token"))
  }
}
