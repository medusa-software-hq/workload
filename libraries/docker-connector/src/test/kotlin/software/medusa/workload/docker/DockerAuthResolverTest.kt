package software.medusa.workload.docker

import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The docker-py auth port: resolution order, the credential-helper protocol, and the failure modes.
 * No real helper binary and no daemon — the helper runner is injected.
 */
class DockerAuthResolverTest {

  private fun configWith(contents: String): Path {
    val dir = Files.createTempDirectory("docker-config")
    val file = dir.resolve("config.json")
    Files.writeString(file, contents)
    return file
  }

  private fun resolver(contents: String, runner: CredentialHelperRunner) =
      DockerAuthResolver(configWith(contents), runner)

  private fun helperReturning(json: String) = CredentialHelperRunner { _, _ ->
    HelperResult(0, json, "")
  }

  @Test
  fun `credHelpers entry for the registry wins`() {
    var seenHelper: String? = null
    val resolver =
        resolver(
            """{"credHelpers": {"us-docker.pkg.dev": "gcloud"}, "credsStore": "desktop"}""",
            { helper, _ ->
              seenHelper = helper
              HelperResult(0, """{"Username":"oauth2accesstoken","Secret":"ya29.abc"}""", "")
            },
        )

    val auth = resolver.resolve("us-docker.pkg.dev")
    assertEquals("gcloud", seenHelper, "the per-registry helper must win over credsStore")
    assertEquals("oauth2accesstoken", auth?.username)
    assertEquals("ya29.abc", auth?.password)
    assertEquals("us-docker.pkg.dev", auth?.serverAddress)
  }

  @Test
  fun `credsStore is used when no credHelpers entry matches`() {
    var seenHelper: String? = null
    val resolver =
        resolver(
            """{"credHelpers": {"other.registry": "somehelper"}, "credsStore": "desktop"}""",
            { helper, _ ->
              seenHelper = helper
              HelperResult(0, """{"Username":"u","Secret":"p"}""", "")
            },
        )

    resolver.resolve("us-docker.pkg.dev")
    assertEquals("desktop", seenHelper)
  }

  @Test
  fun `the token username variant becomes an identity token, not a password`() {
    val resolver =
        resolver(
            """{"credHelpers": {"us-docker.pkg.dev": "gcloud"}}""",
            helperReturning("""{"Username":"<token>","Secret":"identity-token-value"}"""),
        )

    val auth = resolver.resolve("us-docker.pkg.dev")
    assertEquals("identity-token-value", auth?.identityToken)
    assertNull(auth?.password, "a <token> secret must not be sent as a password")
    assertNull(auth?.username)
  }

  @Test
  fun `the registry is passed to the helper on stdin`() {
    var seenRegistry: String? = null
    val resolver =
        resolver(
            """{"credsStore": "gcloud"}""",
            { _, registry ->
              seenRegistry = registry
              HelperResult(0, """{"Username":"u","Secret":"p"}""", "")
            },
        )

    resolver.resolve("europe-docker.pkg.dev")
    assertEquals("europe-docker.pkg.dev", seenRegistry)
  }

  @Test
  fun `a helper with no entry falls through to a static auths entry`() {
    val encoded = Base64.getEncoder().encodeToString("staticuser:staticpass".toByteArray())
    val resolver =
        resolver(
            """{"credsStore": "gcloud", "auths": {"us-docker.pkg.dev": {"auth": "$encoded"}}}""",
            // Empty username+secret is how docker-credential-pass says "nothing here".
            helperReturning("""{"Username":"","Secret":""}"""),
        )

    val auth = resolver.resolve("us-docker.pkg.dev")
    assertEquals("staticuser", auth?.username)
    assertEquals("staticpass", auth?.password)
  }

  @Test
  fun `a helper reporting credentials-not-found falls through rather than failing`() {
    val encoded = Base64.getEncoder().encodeToString("u:p".toByteArray())
    val resolver =
        resolver(
            """{"credsStore": "gcloud", "auths": {"reg.example": {"auth": "$encoded"}}}""",
            { _, _ -> HelperResult(1, "", "credentials not found in native keychain") },
        )

    assertEquals("u", resolver.resolve("reg.example")?.username)
  }

  @Test
  fun `static auths entry decodes base64 user colon password`() {
    val encoded = Base64.getEncoder().encodeToString("alice:s3cr3t:with:colons".toByteArray())
    val resolver =
        resolver("""{"auths": {"reg.example": {"auth": "$encoded"}}}""", helperReturning("{}"))

    val auth = resolver.resolve("reg.example")
    assertEquals("alice", auth?.username)
    // Only the first colon separates; the password may contain more.
    assertEquals("s3cr3t:with:colons", auth?.password)
  }

  @Test
  fun `an unknown registry with no helper and no entry resolves anonymous`() {
    val resolver = resolver("""{"auths": {}}""", helperReturning("{}"))
    assertNull(resolver.resolve("unknown.registry"))
  }

  @Test
  fun `a missing config file resolves anonymous`() {
    val resolver =
        DockerAuthResolver(
            Path.of("/nonexistent/definitely/config.json"),
            helperReturning("{}"),
        )
    assertNull(resolver.resolve("any.registry"))
  }

  @Test
  fun `a helper returning garbage yields one clean actionable error`() {
    val resolver =
        resolver("""{"credsStore": "broken"}""", helperReturning("this is not json at all"))

    val e = assertFailsWith<DockerCredentialException> { resolver.resolve("reg.example") }
    assertTrue("docker-credential-broken" in e.message!!, e.message!!)
    assertTrue("isn't valid JSON" in e.message!!, e.message!!)
  }

  @Test
  fun `a helper failing for a real reason yields one clean actionable error`() {
    val resolver =
        resolver(
            """{"credsStore": "gcloud"}""",
            { _, _ -> HelperResult(2, "", "gcloud: not logged in") },
        )

    val e = assertFailsWith<DockerCredentialException> { resolver.resolve("reg.example") }
    assertTrue("docker-credential-gcloud" in e.message!!, e.message!!)
    assertTrue("gcloud: not logged in" in e.message!!, e.message!!)
  }

  @Test
  fun `X-Registry-Auth is base64url of the docker-py wire shape`() {
    val header =
        RegistryAuth(serverAddress = "reg.example", username = "u", password = "p").toHeaderValue()

    // Must be URL-safe base64 (the header has to survive as an ASCII header value).
    assertTrue(header.none { it == '+' || it == '/' }, header)

    val decoded = String(Base64.getUrlDecoder().decode(header))
    val obj = Json.parseToJsonElement(decoded).jsonObject
    assertEquals("u", obj["Username"]?.jsonPrimitive?.content)
    assertEquals("p", obj["Password"]?.jsonPrimitive?.content)
    assertEquals("reg.example", obj["ServerAddress"]?.jsonPrimitive?.content)
  }
}
