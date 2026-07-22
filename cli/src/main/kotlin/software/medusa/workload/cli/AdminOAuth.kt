package software.medusa.workload.cli

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse.BodyHandlers
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val authEndpoint = "https://accounts.google.com/o/oauth2/v2/auth"
private const val tokenEndpoint = "https://oauth2.googleapis.com/token"

// openid → get an ID token; email → the claim the API's decorator requires.
private const val oauthScopes = "openid email"

private val oauthJson = Json { ignoreUnknownKeys = true }

/** A Google ID token plus the refresh token and the ID token's expiry (epoch seconds). */
data class TokenSet(val idToken: String, val refreshToken: String?, val expiresAtEpochSec: Long)

/**
 * Raised for any failure in the OAuth exchange — carries Google's `error` code where there is one.
 */
class AdminOAuthException(val code: String, val detail: String?) :
    Exception("OAuth failed: $code" + (detail?.let { " ($it)" } ?: ""))

@Serializable
internal data class TokenEndpointResponse(
    @SerialName("id_token") val idToken: String? = null,
    @SerialName("refresh_token") val refreshToken: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null,
    @SerialName("error") val error: String? = null,
    @SerialName("error_description") val errorDescription: String? = null,
)

internal data class CallbackParams(
    val code: String?,
    val state: String?,
    val error: String?,
    val errorDescription: String?,
)

private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun randomUrlSafe(bytes: Int): String {
  val buf = ByteArray(bytes)
  SecureRandom().nextBytes(buf)
  return Base64.getUrlEncoder().withoutPadding().encodeToString(buf)
}

/** PKCE code verifier — a high-entropy URL-safe string (RFC 7636). */
internal fun generateCodeVerifier(): String = randomUrlSafe(32)

/** PKCE S256 challenge: BASE64URL(SHA-256(verifier)). */
internal fun codeChallenge(verifier: String): String {
  val digest =
      MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII))
  return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

internal fun buildAuthUrl(
    clientId: String,
    redirectUri: String,
    challenge: String,
    state: String,
): String {
  val params =
      linkedMapOf(
          "client_id" to clientId,
          "redirect_uri" to redirectUri,
          "response_type" to "code",
          "scope" to oauthScopes,
          "code_challenge" to challenge,
          "code_challenge_method" to "S256",
          "state" to state,
          // Ask for a refresh token, and force the consent screen so we reliably get one.
          "access_type" to "offline",
          "prompt" to "consent",
      )
  return authEndpoint +
      "?" +
      params.entries.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
}

internal fun parseQuery(rawQuery: String?): Map<String, String> {
  if (rawQuery.isNullOrEmpty()) return emptyMap()
  return rawQuery
      .split("&")
      .mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx <= 0) {
          null
        } else {
          URLDecoder.decode(pair.substring(0, idx), StandardCharsets.UTF_8) to
              URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
        }
      }
      .toMap()
}

internal fun toTokenSet(response: TokenEndpointResponse): TokenSet {
  val idToken = response.idToken ?: throw AdminOAuthException("no_id_token", null)
  val expiry =
      AdminJwt.expiresAtEpochSec(idToken)
          ?: (System.currentTimeMillis() / 1000 + (response.expiresIn ?: 3600))
  return TokenSet(idToken, response.refreshToken, expiry)
}

/**
 * A one-shot loopback HTTP server that catches Google's OAuth redirect on an ephemeral localhost
 * port — the standard installed-app flow, no pre-registered redirect URI needed (Google allows any
 * `127.0.0.1:<port>`).
 */
internal class LoopbackReceiver : AutoCloseable {
  private val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
  private val received = ArrayBlockingQueue<CallbackParams>(1)

  val redirectUri: String
    get() = "http://127.0.0.1:${server.address.port}"

  init {
    server.createContext("/") { exchange ->
      val params = parseQuery(exchange.requestURI.rawQuery)
      val body =
          "<html><body style=\"font-family:sans-serif\">Signed in. You can close this tab and " +
              "return to the terminal.</body></html>"
      val bytes = body.toByteArray(StandardCharsets.UTF_8)
      exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
      exchange.sendResponseHeaders(200, bytes.size.toLong())
      exchange.responseBody.use { it.write(bytes) }
      received.offer(
          CallbackParams(
              params["code"],
              params["state"],
              params["error"],
              params["error_description"],
          )
      )
    }
    server.executor = null
    server.start()
  }

  fun awaitCallback(timeout: Duration): CallbackParams =
      received.poll(timeout.toMillis(), TimeUnit.MILLISECONDS)
          ?: throw AdminOAuthException("timeout", "Timed out waiting for the browser sign-in.")

  override fun close() = server.stop(0)
}

/** Opens [url] in the platform browser; returns false if no opener could be launched. */
internal fun openInBrowser(url: String): Boolean {
  val os = System.getProperty("os.name").lowercase()
  val command =
      when {
        "mac" in os || "darwin" in os -> listOf("open", url)
        "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
        else -> listOf("xdg-open", url)
      }
  return runCatching { ProcessBuilder(command).inheritIO().start() }.isSuccess
}

/** Drives the human OAuth sign-in and token refresh for the `admin` commands. */
class AdminOAuth(
    private val clientId: String,
    private val clientSecret: String,
    private val httpClient: HttpClient = HttpClient.newHttpClient(),
    private val tokenEndpointUrl: String = tokenEndpoint,
) {
  /**
   * Runs the loopback + PKCE sign-in: opens the browser, waits for the redirect, exchanges the code
   * for tokens. [openBrowser] and [echo] are injectable for testing.
   */
  fun login(openBrowser: (String) -> Boolean = ::openInBrowser, echo: (String) -> Unit): TokenSet {
    val verifier = generateCodeVerifier()
    val state = randomUrlSafe(16)
    LoopbackReceiver().use { receiver ->
      val url = buildAuthUrl(clientId, receiver.redirectUri, codeChallenge(verifier), state)
      echo("Opening your browser to sign in…")
      if (!openBrowser(url)) {
        echo("Couldn't open a browser automatically. Open this URL to continue:\n$url")
      }
      val callback = receiver.awaitCallback(Duration.ofMinutes(5))
      if (callback.error != null)
          throw AdminOAuthException(callback.error, callback.errorDescription)
      if (callback.state != state) {
        throw AdminOAuthException("state_mismatch", "OAuth state did not match; aborting.")
      }
      val code =
          callback.code ?: throw AdminOAuthException("no_code", "No authorization code returned.")
      return exchangeCode(code, verifier, receiver.redirectUri)
    }
  }

  private fun exchangeCode(code: String, verifier: String, redirectUri: String): TokenSet =
      tokenRequest(
          mapOf(
              "client_id" to clientId,
              "client_secret" to clientSecret,
              "code" to code,
              "code_verifier" to verifier,
              "grant_type" to "authorization_code",
              "redirect_uri" to redirectUri,
          )
      )

  /** Mints a fresh ID token from a stored refresh token (no browser). */
  fun refresh(refreshToken: String): TokenSet =
      tokenRequest(
          mapOf(
              "client_id" to clientId,
              "client_secret" to clientSecret,
              "refresh_token" to refreshToken,
              "grant_type" to "refresh_token",
          )
      )

  private fun tokenRequest(form: Map<String, String>): TokenSet {
    val body = form.entries.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
    val request =
        HttpRequest.newBuilder(URI.create(tokenEndpointUrl))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
    val response = httpClient.send(request, BodyHandlers.ofString())
    val parsed = oauthJson.decodeFromString<TokenEndpointResponse>(response.body())
    if (response.statusCode() !in 200..299 || parsed.idToken == null) {
      throw AdminOAuthException(
          parsed.error ?: "http_${response.statusCode()}",
          parsed.errorDescription,
      )
    }
    return toTokenSet(parsed)
  }
}
