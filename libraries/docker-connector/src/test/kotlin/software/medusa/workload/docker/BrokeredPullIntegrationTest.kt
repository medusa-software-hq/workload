package software.medusa.workload.docker

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The live-GCP half of stage 3, run only by the manually-triggered `integration-test-registry-auth`
 * workflow: does a **brokered** token — one minted by impersonating a profile's target service
 * account — really pull a private Artifact Registry image, and is it really refused when that
 * account lacks `roles/artifactregistry.reader`?
 *
 * Every other part of this path is covered by unit and contract tests; this closes the one hop they
 * can't reach. Skipped everywhere else: without [imageRefEnvVar] there's no fixture to talk to.
 *
 * The two tokens differ *only* in whether their service account holds the reader grant, so a
 * disagreement between them isolates exactly that.
 */
class BrokeredPullIntegrationTest {

  private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

  private fun withFixture(block: suspend (DockerConnector, String) -> Unit) {
    val imageRef = env(imageRefEnvVar)
    if (imageRef == null) {
      assumeTrue(false, "$imageRefEnvVar not set; not the registry-auth integration-test workflow")
    }
    DockerConnector(DockerConnectorConfig.fromEnvironment()).use { connector ->
      runBlocking { block(connector, imageRef!!) }
    }
  }

  private fun brokeredAuth(host: String, token: String) =
      RegistryAuth(serverAddress = host, username = "oauth2accesstoken", password = token)

  private fun hostOf(ref: String) = ref.substringBefore('/')

  @Test
  fun `a brokered token for an SA with the reader grant pulls a private image`() =
      withFixture { connector, imageRef ->
        val token =
            env(readerTokenEnvVar)
                ?: error("$readerTokenEnvVar must be set alongside $imageRefEnvVar")

        // Nothing on this machine is signed in to this registry: the pull can only work because the
        // impersonated target SA is authorized. That is the entire claim of stage 3.
        val progress =
            connector.images.pull(imageRef, brokeredAuth(hostOf(imageRef), token)).toList()

        assertTrue(progress.isNotEmpty(), "a real pull should report progress")
        assertTrue(
            connector.images.inspect(imageRef).id.isNotBlank(),
            "the image should be present",
        )
      }

  @Test
  fun `a brokered token for an SA without the reader grant is refused, and we say why`() =
      withFixture { connector, imageRef ->
        val token =
            env(noReaderTokenEnvVar)
                ?: error("$noReaderTokenEnvVar must be set alongside $imageRefEnvVar")

        val e =
            assertFailsWith<DockerConnectorException> {
              connector.images.pull(imageRef, brokeredAuth(hostOf(imageRef), token)).toList()
            }

        // Whichever shape the daemon reports it in, it must be typed and it must read like an auth
        // failure — that is what drives the CLI's artifactregistry.reader remediation.
        assertTrue(
            e is DockerApiException || e is DockerPullException,
            "expected a typed pull failure, got ${e::class.simpleName}: ${e.message}",
        )
        val message = e.message.orEmpty().lowercase()
        assertTrue(
            listOf("denied", "unauthorized", "forbidden", "authentication").any { it in message },
            "expected an auth-flavoured denial, got: ${e.message}",
        )
      }

  @Test
  fun `an anonymous pull of the same private image is refused`() =
      withFixture { connector, imageRef ->
        // Proves the fixture really is private — otherwise the positive test above would pass even
        // if
        // the brokered token were ignored entirely.
        assertFailsWith<DockerConnectorException> { connector.images.pull(imageRef, null).toList() }
      }

  @Test
  fun `pulling by digest with a brokered token works`() = withFixture { connector, imageRef ->
    val token = env(readerTokenEnvVar) ?: error("$readerTokenEnvVar must be set")
    val auth = brokeredAuth(hostOf(imageRef), token)

    connector.images.pull(imageRef, auth).toList()
    val digestRef =
        connector.images.inspect(imageRef).repoDigests.firstOrNull()
            ?: error("the pulled image should report a repo digest")

    connector.images.pull(digestRef, brokeredAuth(hostOf(digestRef), token)).toList()
    assertEquals(
        connector.images.inspect(imageRef).id,
        connector.images.inspect(digestRef).id,
        "the digest ref should resolve to the same image `workload run` would pin",
    )
  }

  private companion object {
    const val imageRefEnvVar = "IT_IMAGE_REF"
    const val readerTokenEnvVar = "IT_READER_TOKEN"
    const val noReaderTokenEnvVar = "IT_NO_READER_TOKEN"
  }
}
