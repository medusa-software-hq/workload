package software.medusa.workload.runtime

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A minimal stand-in for the Secret Manager REST API's `:access` endpoint. */
class SecretResolverTest {

  private lateinit var server: HttpServer
  private lateinit var baseUrl: String
  private var lastAuthorizationHeader: String? = null

  @BeforeTest
  fun start() {
    server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/") { exchange ->
      lastAuthorizationHeader = exchange.requestHeaders.getFirst("Authorization")
      val path = exchange.requestURI.path

      val (status, body) =
          when {
            path.endsWith("/secrets/api-key/versions/latest:access") -> {
              val encoded = Base64.getEncoder().encodeToString("super-secret-value".toByteArray())
              200 to """{"payload":{"data":"$encoded"}}"""
            }
            path.endsWith("/secrets/forbidden/versions/latest:access") -> 403 to "{}"
            path.endsWith("/secrets/missing/versions/latest:access") -> 404 to "{}"
            path.endsWith("/secrets/broken/versions/latest:access") -> 200 to "not json"
            else -> 500 to "{}"
          }

      val bytes = body.toByteArray()
      exchange.sendResponseHeaders(status, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
    }
    server.start()
    baseUrl = "http://127.0.0.1:${server.address.port}"
  }

  @AfterTest
  fun stop() {
    server.stop(0)
  }

  @Test
  fun `resolves a secret to its plaintext value using the given access token`() {
    val resolved =
        resolveSecrets(
            mapOf("API_KEY" to "projects/p/secrets/api-key/versions/latest"),
            accessToken = "impersonated-token",
            baseUrl = baseUrl,
        )

    assertEquals(mapOf("API_KEY" to "super-secret-value"), resolved)
    assertEquals("Bearer impersonated-token", lastAuthorizationHeader)
  }

  @Test
  fun `resolves multiple secrets`() {
    val resolved =
        resolveSecrets(
            mapOf(
                "API_KEY" to "projects/p/secrets/api-key/versions/latest",
                "OTHER" to "projects/p/secrets/api-key/versions/latest",
            ),
            accessToken = "token",
            baseUrl = baseUrl,
        )

    assertEquals("super-secret-value", resolved["API_KEY"])
    assertEquals("super-secret-value", resolved["OTHER"])
  }

  @Test
  fun `an empty map returns an empty map without any HTTP call`() {
    assertEquals(emptyMap(), resolveSecrets(emptyMap(), accessToken = "unused", baseUrl = baseUrl))
    assertEquals(null, lastAuthorizationHeader)
  }

  @Test
  fun `a 403 produces an ask-the-owning-project remediation message, not a stack trace`() {
    val exception =
        assertFailsWith<SecretResolutionException> {
          resolveSecrets(
              mapOf("API_KEY" to "projects/p/secrets/forbidden/versions/latest"),
              accessToken = "token",
              baseUrl = baseUrl,
          )
        }
    assertEquals("API_KEY", exception.envVarName)
    assertTrue(exception.message!!.contains("Ask the project"))
    assertTrue(exception.message!!.contains("secretAccessor"))
  }

  @Test
  fun `a 404 names the secret as not found`() {
    val exception =
        assertFailsWith<SecretResolutionException> {
          resolveSecrets(
              mapOf("API_KEY" to "projects/p/secrets/missing/versions/latest"),
              accessToken = "token",
              baseUrl = baseUrl,
          )
        }
    assertTrue(exception.message!!.contains("not found"))
  }

  @Test
  fun `a malformed response is reported distinctly from a permission or not-found error`() {
    val exception =
        assertFailsWith<SecretResolutionException> {
          resolveSecrets(
              mapOf("API_KEY" to "projects/p/secrets/broken/versions/latest"),
              accessToken = "token",
              baseUrl = baseUrl,
          )
        }
    assertTrue(exception.message!!.contains("Malformed"))
  }

  @Test
  fun `resolution is all-or-nothing -- the first failure aborts before later secrets are tried`() {
    assertFailsWith<SecretResolutionException> {
      resolveSecrets(
          linkedMapOf(
              "MISSING" to "projects/p/secrets/missing/versions/latest",
              "API_KEY" to "projects/p/secrets/api-key/versions/latest",
          ),
          accessToken = "token",
          baseUrl = baseUrl,
      )
    }
  }

  @Test
  fun `a network error (nothing listening) is reported, not a crash`() {
    val exception =
        assertFailsWith<SecretResolutionException> {
          resolveSecrets(
              mapOf("API_KEY" to "projects/p/secrets/api-key/versions/latest"),
              accessToken = "token",
              baseUrl = "http://127.0.0.1:1",
          )
        }
    assertEquals("API_KEY", exception.envVarName)
  }

  @Test
  fun `no exception message ever contains the resolved secret value`() {
    val resolved =
        resolveSecrets(
            mapOf("API_KEY" to "projects/p/secrets/api-key/versions/latest"),
            accessToken = "token",
            baseUrl = baseUrl,
        )
    // Sanity check the fixture actually returns a distinctive value we can search for.
    assertEquals("super-secret-value", resolved["API_KEY"])

    val exception =
        assertFailsWith<SecretResolutionException> {
          resolveSecrets(
              mapOf(
                  "API_KEY" to "projects/p/secrets/api-key/versions/latest",
                  "MISSING" to "projects/p/secrets/missing/versions/latest",
              ),
              accessToken = "token",
              baseUrl = baseUrl,
          )
        }
    assertTrue(!exception.message!!.contains("super-secret-value"))
  }

  @Test
  fun `redactedEnvSummary keeps plain values and redacts secret-backed ones`() {
    val summary =
        redactedEnvSummary(
            envVars = mapOf("MODE" to "batch"),
            secretEnvVars = mapOf("API_KEY" to "projects/p/secrets/api-key/versions/latest"),
        )

    assertEquals(mapOf("MODE" to "batch", "API_KEY" to "***"), summary)
  }

  @Test
  fun `redactedEnvSummary never contains a resource name`() {
    val summary =
        redactedEnvSummary(
            envVars = emptyMap(),
            secretEnvVars = mapOf("DB_PASSWORD" to "projects/p/secrets/db-password/versions/3"),
        )

    assertEquals("***", summary["DB_PASSWORD"])
    assertTrue(summary.values.none { it.contains("projects/") })
  }
}
