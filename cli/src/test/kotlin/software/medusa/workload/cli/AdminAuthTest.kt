package software.medusa.workload.cli

import com.github.ajalt.clikt.core.CliktError
import com.google.auth.oauth2.AccessToken
import com.google.auth.oauth2.GoogleCredentials
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class AdminAuthMethodTest {
  private val env = Environment.Prod
  private val serviceToken: () -> String = { "service-id-token" }
  private val humanToken: () -> String = { "human-id-token" }

  @Test
  fun `gsi uses the human provider and never touches service credentials`() {
    var serviceProbed = false
    val provider =
        adminTokenProvider(
            env,
            AdminAuthMethod.GSI,
            service = {
              serviceProbed = true
              serviceToken
            },
            human = { humanToken },
        )
    assertEquals("human-id-token", provider())
    assertEquals(false, serviceProbed, "gsi must not probe for service credentials")
  }

  @Test
  fun `sa uses ambient service credentials when present`() {
    val provider =
        adminTokenProvider(
            env,
            AdminAuthMethod.SA,
            service = { serviceToken },
            human = { error("sa must not fall back to human") },
        )
    assertEquals("service-id-token", provider())
  }

  @Test
  fun `sa fails cleanly when no ambient credentials can mint a token`() {
    val error =
        assertFailsWith<CliktError> {
          adminTokenProvider(
              env,
              AdminAuthMethod.SA,
              service = { null },
              human = { error("sa must not fall back to human") },
          )
        }
    assertEquals(true, error.message!!.contains("Service-account auth"))
  }
}

class AdminAuthMethodFromEnvTest {
  @Test
  fun `gsi and sa parse case-insensitively and trimmed`() {
    assertEquals(AdminAuthMethod.GSI, AdminAuthMethod.fromEnv("gsi"))
    assertEquals(AdminAuthMethod.GSI, AdminAuthMethod.fromEnv(" GSI "))
    assertEquals(AdminAuthMethod.SA, AdminAuthMethod.fromEnv("sa"))
    assertEquals(AdminAuthMethod.SA, AdminAuthMethod.fromEnv("SA"))
  }

  @Test
  fun `unset or blank yields null so the caller defaults to gsi`() {
    assertNull(AdminAuthMethod.fromEnv(null))
    assertNull(AdminAuthMethod.fromEnv(""))
    assertNull(AdminAuthMethod.fromEnv("   "))
  }

  @Test
  fun `an unknown value is a clean error naming the accepted values`() {
    val error = assertFailsWith<IllegalArgumentException> { AdminAuthMethod.fromEnv("human") }
    assertEquals(true, error.message!!.contains("gsi"))
    assertEquals(true, error.message!!.contains("sa"))
  }
}

class ServiceIdTokenProviderTest {
  @Test
  fun `no discoverable ADC yields null so sa can report a clean error`() {
    val provider =
        serviceIdTokenProvider(
            "https://api.example",
            loadCredentials = { throw IOException("no ADC") },
        )
    assertNull(provider)
  }

  @Test
  fun `credentials that cannot mint ID tokens yield null`() {
    // A bare access-token credential is not an IdTokenProvider. (Real `gcloud auth
    // application-default login` user credentials, by contrast, *are* one in this google-auth
    // version — which is the whole reason auth is chosen explicitly rather than auto-detected.)
    val bareAccessToken = GoogleCredentials.create(AccessToken("ya29.fake", null))
    val provider =
        serviceIdTokenProvider("https://api.example", loadCredentials = { bareAccessToken })
    assertNull(provider)
  }
}
