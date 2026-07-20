package software.medusa.workload.docker

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

/**
 * Exercises the *real* credential-helper subprocess against a helper script on disk — the mechanics
 * [DockerAuthResolverTest] deliberately stubs out (it injects a runner to test the protocol logic).
 * Together they cover the whole path without needing `docker-credential-gcloud` installed.
 */
@DisabledOnOs(OS.WINDOWS)
class CredentialHelperProcessTest {

  /** Writes an executable fake helper that runs [script] with the registry available on stdin. */
  private fun fakeHelper(name: String, script: String): Path {
    val dir = Files.createTempDirectory("cred-helper")
    val file = dir.resolve("docker-credential-$name")
    Files.writeString(file, "#!/bin/sh\n$script\n")
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"))
    return file
  }

  @Test
  fun `the registry is written to the helper's stdin and its stdout is captured`() {
    // Echoes back whatever it read on stdin, proving the registry actually reaches the helper.
    val helper =
        fakeHelper("echoback", """read reg; echo "{\"Username\":\"u\",\"Secret\":\"${'$'}reg\"}"""")

    val result = runCredentialHelperProgram(helper.toString(), "us-docker.pkg.dev")

    assertEquals(0, result.exitCode)
    assertTrue("us-docker.pkg.dev" in result.stdout, result.stdout)
  }

  @Test
  fun `a non-zero exit and stderr are both captured, not thrown`() {
    val helper = fakeHelper("failing", """echo "boom" >&2; exit 3""")

    val result = runCredentialHelperProgram(helper.toString(), "reg.example")

    assertEquals(3, result.exitCode)
    assertTrue("boom" in result.stderr, result.stderr)
  }

  @Test
  fun `a missing helper binary is a clean actionable error, not a raw IOException`() {
    val e =
        assertFailsWith<DockerCredentialException> {
          runCredentialHelperProgram("/nonexistent/docker-credential-nope", "reg.example")
        }
    assertTrue("isn't installed or isn't on PATH" in e.message!!, e.message!!)
  }

  @Test
  fun `a real helper script resolves end-to-end through DockerAuthResolver`() {
    // The full path: config.json -> credHelpers -> run the binary -> parse -> RegistryAuth.
    val helper = fakeHelper("gcloudish", """echo '{"Username":"<token>","Secret":"ya29.real"}'""")
    val configDir = Files.createTempDirectory("docker-config")
    val config = configDir.resolve("config.json")
    Files.writeString(config, """{"credHelpers": {"us-docker.pkg.dev": "gcloudish"}}""")

    // Resolve via an absolute path so the test doesn't depend on PATH.
    val resolver =
        DockerAuthResolver(config) { name, registry ->
          runCredentialHelperProgram(
              helper.parent.resolve("docker-credential-$name").toString(),
              registry,
          )
        }

    val auth = resolver.resolve("us-docker.pkg.dev")
    assertEquals("ya29.real", auth?.identityToken)
    assertEquals("us-docker.pkg.dev", auth?.serverAddress)
  }
}
