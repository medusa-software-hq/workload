package software.medusa.workload.server

import com.google.cloud.iam.credentials.v1.IamCredentialsClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * The live-GCP half of revision verification, run only by the manually-triggered
 * `integration-test-registry-auth` workflow.
 *
 * Unlike [BrokeredPullIntegrationTest] — which consumes a token the workflow minted — this drives
 * the **real [GcpImageDigestResolver]**: it mints the impersonated token itself through
 * `IamCredentialsClient` and HEADs the real Artifact Registry manifest, exactly as the backend does
 * when a revision is created. So this covers our minting code, not just the registry's answer.
 *
 * The story's promise is that a missing `artifactregistry.reader` grant **flags the revision at
 * verification** rather than blowing up mid-pull on a worker. That's the difference between the two
 * accounts below.
 */
class ImageDigestResolverIntegrationTest {

  private fun env(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

  private fun fixture(): Triple<String, String, String>? {
    val imageRef = env("IT_IMAGE_REF") ?: return null
    val readerSa = env("IT_READER_SA_EMAIL") ?: return null
    val noReaderSa = env("IT_NO_READER_SA_EMAIL") ?: return null
    return Triple(imageRef, readerSa, noReaderSa)
  }

  private fun withFixture(block: suspend (GcpImageDigestResolver, String, String, String) -> Unit) {
    val f = fixture()
    if (f == null) {
      assumeTrue(
          false,
          "IT_* fixture vars not set; not the registry-auth integration-test workflow",
      )
      return
    }
    val (imageRef, readerSa, noReaderSa) = f
    // ADC here is the workflow's federated CI identity, which holds serviceAccountTokenCreator on
    // both accounts — the same role the broker's runtime SA holds on a real target SA.
    IamCredentialsClient.create().use { client ->
      val resolver = GcpImageDigestResolver(client)
      runBlocking { block(resolver, imageRef, readerSa, noReaderSa) }
    }
  }

  @Test
  fun `a target SA with the reader grant resolves a real digest`() =
      withFixture { resolver, imageRef, readerSa, _ ->
        val resolution = resolver.resolve(readerSa, imageRef)

        assertEquals(
            ImageStatus.RESOLVED,
            resolution.status,
            "expected a resolved digest, got ${resolution.status}: ${resolution.detail}",
        )
        assertNotNull(resolution.digest)
        assertTrue(
            resolution.digest!!.startsWith("sha256:"),
            "expected a real content digest, got ${resolution.digest}",
        )
      }

  @Test
  fun `a target SA without the reader grant flags the revision instead of resolving`() =
      withFixture { resolver, imageRef, _, noReaderSa ->
        val resolution = resolver.resolve(noReaderSa, imageRef)

        // UNRESOLVABLE (not UNDETERMINED): a 403 is a verdict, not a transient blip. This is what
        // makes the revision non-claimable up front rather than failing on a worker mid-pull.
        assertEquals(
            ImageStatus.UNRESOLVABLE,
            resolution.status,
            "a missing reader grant must be a permanent verdict, got: ${resolution.detail}",
        )
        assertNull(resolution.digest, "nothing should be pinned when the SA can't read the image")
      }

  @Test
  fun `the same image resolves or fails purely on the reader grant`() =
      withFixture { resolver, imageRef, readerSa, noReaderSa ->
        // Same image, same code path, two accounts identical but for one IAM binding — so the
        // difference isolates the grant itself and nothing else.
        val granted = resolver.resolve(readerSa, imageRef)
        val denied = resolver.resolve(noReaderSa, imageRef)

        assertEquals(ImageStatus.RESOLVED, granted.status)
        assertEquals(ImageStatus.UNRESOLVABLE, denied.status)
      }

  @Test
  fun `a nonexistent tag in a readable repo is also a verdict`() =
      withFixture { resolver, imageRef, readerSa, _ ->
        val missing = imageRef.substringBeforeLast(':') + ":definitely-not-a-real-tag-9f8e7d"
        val resolution = resolver.resolve(readerSa, missing)

        assertEquals(ImageStatus.UNRESOLVABLE, resolution.status, resolution.detail)
      }
}
