package software.medusa.workload.cli

import java.net.InetAddress
import java.net.InetSocketAddress
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The M4-B4 `exec` path end to end, no Docker and no GCP: a loopback metadata emulator (fake token
 * source) and a real child process that fetches a token from it via the injected `GCE_METADATA_*`
 * pointers — with no token in the child's own env. Uses `curl`; skips where it isn't installed.
 */
class ExecMetadataTest {

  private val fakeToken = "ya29.exec-beacon-fake"

  private fun curlAvailable(): Boolean =
      runCatching { ProcessBuilder("sh", "-c", "command -v curl").start().waitFor() == 0 }
          .getOrDefault(false)

  @Test
  fun `a child fetches a token from the loopback emulator via the pointer env, with none in its env`() {
    assumeTrue(curlAvailable(), "curl not available; skipping exec metadata test")

    MetadataEmulator(
            RefreshingTokenCache({
              BrokeredToken(
                  accessToken = fakeToken,
                  expiresAt = Instant.now().plusSeconds(3600),
                  serviceAccountEmail = "runner@proj.iam.gserviceaccount.com",
              )
            }),
            InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            InetAddress::isLoopbackAddress,
        )
        .use { emu ->
          emu.start()
          val childEnv =
              buildChildEnvWithMetadata(
                  inherited = mapOf("PATH" to (System.getenv("PATH") ?: "/usr/bin:/bin")),
                  profileEnv = emptyMap(),
                  pointerEnv = metadataPointerEnv(emu.hostPort),
              )

          // The credential must not be in the child's env — only the pointer.
          assertFalse(googleOauthAccessTokenEnvVar in childEnv.variables)
          assertFalse(cloudsdkAuthAccessTokenEnvVar in childEnv.variables)

          val script =
              "curl -s -H 'Metadata-Flavor: Google' " +
                  "http://${'$'}GCE_METADATA_HOST/computeMetadata/v1/instance/service-accounts/default/token"
          val pb = ProcessBuilder("sh", "-c", script).redirectErrorStream(true)
          pb.environment().apply {
            clear()
            putAll(childEnv.variables)
          }
          val process = pb.start()
          val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
          process.waitFor()

          assertTrue(fakeToken in output, "the child should fetch the brokered token; got: $output")
        }
  }
}
