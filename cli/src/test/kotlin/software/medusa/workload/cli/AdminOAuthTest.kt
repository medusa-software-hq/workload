package software.medusa.workload.cli

import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

private fun b64url(text: String): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray())

/**
 * A JWT with the given payload JSON; header/signature are irrelevant to the payload-only reader.
 */
internal fun fakeJwt(payloadJson: String): String = "${b64url("{}")}.${b64url(payloadJson)}.sig"

class AdminOAuthTest {
  @Test
  fun `PKCE challenge is the base64url sha256 of the verifier`() {
    val verifier = generateCodeVerifier()
    val expected =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(
                MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
            )
    assertEquals(expected, codeChallenge(verifier))
  }

  @Test
  fun `code verifier is url-safe and non-trivial`() {
    val verifier = generateCodeVerifier()
    assertTrue(verifier.length >= 43, "RFC 7636 requires >= 43 chars")
    assertTrue(verifier.all { it.isLetterOrDigit() || it == '-' || it == '_' })
  }

  @Test
  fun `auth url carries the PKCE, client, and offline-consent params`() {
    val url = buildAuthUrl("client-x", "http://127.0.0.1:5555", "challenge-y", "state-z")
    assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
    for (part in
        listOf(
            "client_id=client-x",
            "redirect_uri=http%3A%2F%2F127.0.0.1%3A5555",
            "response_type=code",
            "scope=openid+email",
            "code_challenge=challenge-y",
            "code_challenge_method=S256",
            "state=state-z",
            "access_type=offline",
            "prompt=consent",
        )) {
      assertTrue(part in url, "missing $part in $url")
    }
  }

  @Test
  fun `parseQuery decodes pairs and tolerates junk`() {
    val parsed = parseQuery("code=abc%2F123&state=xyz&empty")
    assertEquals("abc/123", parsed["code"])
    assertEquals("xyz", parsed["state"])
    assertNull(parsed["empty"])
    assertTrue(parseQuery(null).isEmpty())
  }

  @Test
  fun `toTokenSet reads expiry from the id token exp claim`() {
    val token = fakeJwt("""{"exp":1893456000}""")
    val set =
        toTokenSet(TokenEndpointResponse(idToken = token, refreshToken = "r", expiresIn = 3600))
    assertEquals(1893456000L, set.expiresAtEpochSec)
    assertEquals("r", set.refreshToken)
  }

  @Test
  fun `token endpoint error response parses`() {
    val parsed =
        Json { ignoreUnknownKeys = true }
            .decodeFromString<TokenEndpointResponse>(
                """{"error":"invalid_grant","error_description":"Token has been expired or revoked."}"""
            )
    assertEquals("invalid_grant", parsed.error)
    assertNull(parsed.idToken)
  }
}

class AdminJwtTest {
  @Test
  fun `reads email and exp from the payload`() {
    val token =
        fakeJwt("""{"email":"admin@medusa.software","exp":1893456000,"hd":"medusa.software"}""")
    assertEquals("admin@medusa.software", AdminJwt.email(token))
    assertEquals(1893456000L, AdminJwt.expiresAtEpochSec(token))
  }

  @Test
  fun `returns null on a non-jwt`() {
    assertNull(AdminJwt.email("nonsense"))
    assertNull(AdminJwt.expiresAtEpochSec("nonsense"))
  }
}

class AdminConfigResolveTest {
  @Test
  fun `env overrides baked overrides default, and blanks are skipped`() {
    assertEquals("env", AdminConfig.resolve("env", "baked", "default"))
    assertEquals("baked", AdminConfig.resolve(null, "baked", "default"))
    assertEquals("baked", AdminConfig.resolve("  ", "baked", "default"))
    assertEquals("default", AdminConfig.resolve(null, "", "default"))
    assertNull(AdminConfig.resolve(null, null, null))
  }
}

class AdminProfileTableTest {
  @Test
  fun `empty profiles renders a friendly note`() {
    assertEquals("No profiles.", formatProfileTable(emptyList()))
  }

  @Test
  fun `table has a header and one aligned row per profile`() {
    val table =
        formatProfileTable(
            listOf(
                AdminProfile(
                    "hand-test-1",
                    "Hand test",
                    2,
                    archived = false,
                    createdAt = "2026-07-17",
                ),
                AdminProfile("legacy-2", "Legacy", 1, archived = true, createdAt = "2026-01-02"),
            )
        )
    val lines = table.lines()
    assertEquals(3, lines.size)
    assertTrue(lines[0].startsWith("PROFILE ID"))
    assertTrue(lines[1].contains("hand-test-1") && lines[1].contains("active"))
    assertTrue(lines[2].contains("legacy-2") && lines[2].contains("archived"))
  }
}
