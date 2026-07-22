package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktError
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AdminAuthModeTest {
  private val env = Environment.Prod
  private val serviceToken: () -> String = { "service-id-token" }
  private val humanToken: () -> String = { "human-id-token" }

  @Test
  fun `human mode uses the human provider and never touches service credentials`() {
    var serviceProbed = false
    val provider =
        adminTokenProvider(
            env,
            AdminAuthMode.HUMAN,
            service = {
              serviceProbed = true
              serviceToken
            },
            human = { humanToken },
        )
    assertEquals("human-id-token", provider())
    assertEquals(false, serviceProbed, "human mode must not probe for service credentials")
  }

  @Test
  fun `service mode uses ambient credentials when present`() {
    val provider =
        adminTokenProvider(
            env,
            AdminAuthMode.SERVICE,
            service = { serviceToken },
            human = { error("must not fall back to human under --auth=service") },
        )
    assertEquals("service-id-token", provider())
  }

  @Test
  fun `service mode fails cleanly when no ambient credentials can mint a token`() {
    val error =
        assertFailsWith<CliktError> {
          adminTokenProvider(
              env,
              AdminAuthMode.SERVICE,
              service = { null },
              human = { error("must not fall back to human under --auth=service") },
          )
        }
    assertEquals(true, error.message!!.contains("--auth=service"))
  }

  @Test
  fun `auto prefers service credentials when available`() {
    val provider =
        adminTokenProvider(
            env,
            AdminAuthMode.AUTO,
            service = { serviceToken },
            human = { error("auto must not use human when service is available") },
        )
    assertEquals("service-id-token", provider())
  }

  @Test
  fun `auto falls back to human when no service credentials are present`() {
    val provider =
        adminTokenProvider(
            env,
            AdminAuthMode.AUTO,
            service = { null },
            human = { humanToken },
        )
    assertEquals("human-id-token", provider())
  }
}

class ServiceIdTokenProviderTest {
  @Test
  fun `no discoverable ADC yields null so auto can fall back`() {
    val provider =
        serviceIdTokenProvider(
            "https://api.example",
            loadCredentials = { throw IOException("no ADC") },
        )
    assertEquals(null, provider)
  }

  @Test
  fun `credentials that cannot mint ID tokens yield null`() {
    // A plain access-token credential is not an IdTokenProvider (only SAs, impersonated SAs, and
    // WIF
    // external accounts are) — the common `gcloud auth application-default login` dev case.
    val userLike = GoogleCredentials.create(AccessToken("ya29.fake", null))
    val provider = serviceIdTokenProvider("https://api.example", loadCredentials = { userLike })
    assertEquals(null, provider)
  }
}
