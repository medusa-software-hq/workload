package software.medusa.workload.server

import java.net.http.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The regression test for the token-exfiltration hole: a profile's image ref supplies the registry
 * host, and resolving a digest means sending an access token impersonating the profile's target SA
 * to that host. A non-Google host must be refused **before** any token is minted — otherwise
 * whoever set the image ref is handed a working credential for that service account.
 *
 * The minter here fails the test if it is ever called, so "no token was minted" is asserted
 * directly rather than inferred.
 */
class ImageDigestResolverHostGuardTest {

  private fun resolverThatMustNotMint() =
      GcpImageDigestResolver(
          minter = { error("a token was minted for a non-Google registry") },
          httpClient = HttpClient.newHttpClient(),
      )

  @Test
  fun `a non-Google registry is refused without minting a token`() = runBlocking {
    val resolution =
        resolverThatMustNotMint().resolve("sa@p.iam.gserviceaccount.com", "attacker.example/x:v1")

    assertEquals(ImageStatus.UNRESOLVABLE, resolution.status)
    assertTrue(
        "not a Google container registry" in (resolution.detail ?: ""),
        "expected the host guard's reason, got: ${resolution.detail}",
    )
  }

  @Test
  fun `lookalike hosts are refused without minting a token`() = runBlocking {
    val hosts =
        listOf(
            "us-docker.pkg.dev.evil.com",
            "evil-pkg.dev",
            "gcr.io.evil.com",
            "us-docker.pkg.dev:8080",
            "ghcr.io",
            "docker.io",
        )
    for (host in hosts) {
      val resolution =
          resolverThatMustNotMint().resolve("sa@p.iam.gserviceaccount.com", "$host/x:v1")
      assertEquals(ImageStatus.UNRESOLVABLE, resolution.status, "host '$host' must be refused")
    }
  }

  @Test
  fun `a malformed ref is refused without minting a token`() = runBlocking {
    val resolution = resolverThatMustNotMint().resolve("sa@p.iam.gserviceaccount.com", "not-a-ref")
    assertEquals(ImageStatus.UNRESOLVABLE, resolution.status)
  }

  @Test
  fun `a Google registry does reach the minting step`() = runBlocking {
    // The mirror image of the tests above: proves the guard isn't simply refusing everything.
    var minted = false
    val resolver =
        GcpImageDigestResolver(
            minter = {
              minted = true
              error("stop here — the network call is not what this test is about")
            },
            httpClient = HttpClient.newHttpClient(),
        )

    resolver.resolve("sa@p.iam.gserviceaccount.com", "us-docker.pkg.dev/p/repo/app:v1")

    assertTrue(minted, "a Google registry should get past the host guard to the mint")
  }
}
