package software.medusa.workload.runtime

import com.google.api.client.http.HttpTransport
import com.google.api.client.http.LowLevelHttpRequest
import com.google.api.client.http.LowLevelHttpResponse
import com.google.auth.http.HttpTransportFactory
import com.google.auth.oauth2.ComputeEngineCredentials
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An [HttpTransport] that routes every request to wherever the emulator actually listens, over a
 * plain [HttpURLConnection] — this transport only ever reaches the metadata server. Rewriting here
 * keeps the test from mutating the JVM's environment (which JDK 21 makes deliberately hard) while
 * still driving the *real* google-auth client end to end. (google-http-client's own transports are
 * final with protected request builders, so we can neither subclass nor delegate — hence this
 * minimal implementation, sufficient for the metadata server's header-only GET requests.)
 */
private class RewritingTransport(private val hostPort: String) : HttpTransport() {
  override fun buildRequest(method: String, url: String): LowLevelHttpRequest {
    // Route every request to the emulator, whatever host google-auth targeted; keep path + query.
    // google-auth uses `metadata.google.internal` only when `GCE_METADATA_HOST`/`GCE_METADATA_IP`
    // are unset; the Flow worker sets them (Beacon), so google-auth uses that host instead —
    // rewriting only the hardcoded host left the test non-hermetic, reaching the real metadata
    // server inside the worker container.
    val src = URI.create(url)
    val rewritten = "http://$hostPort" + (src.rawPath ?: "") + (src.rawQuery?.let { "?$it" } ?: "")
    val conn = URI.create(rewritten).toURL().openConnection() as HttpURLConnection
    conn.requestMethod = method
    return object : LowLevelHttpRequest() {
      override fun addHeader(name: String, value: String) = conn.addRequestProperty(name, value)

      override fun execute(): LowLevelHttpResponse {
        conn.connect()
        val status = conn.responseCode
        val stream = if (status < 400) conn.inputStream else conn.errorStream
        val headers =
            conn.headerFields.entries
                .filter { it.key != null }
                .flatMap { (k, vs) -> vs.map { k to it } }
        return object : LowLevelHttpResponse() {
          override fun getContent(): InputStream? = stream

          override fun getContentEncoding(): String? = conn.contentEncoding

          override fun getContentLength(): Long = conn.contentLengthLong

          override fun getContentType(): String? = conn.contentType

          override fun getReasonPhrase(): String? = conn.responseMessage

          override fun getStatusCode(): Int = status

          override fun getStatusLine(): String? = null

          override fun getHeaderCount(): Int = headers.size

          override fun getHeaderName(index: Int): String = headers[index].first

          override fun getHeaderValue(index: Int): String = headers[index].second
        }
      }
    }
  }
}

/**
 * The B1 acceptance test: a genuine Google auth client ([ComputeEngineCredentials], the same class
 * that runs inside any GCE-hosted google-auth workload) obtains a token from the emulator and,
 * after the emulator's cached token is force-expired, transparently obtains a fresh one — no
 * client-side error, no code change.
 */
class MetadataEmulatorContractTest {
  @Test
  fun `google-auth ComputeEngineCredentials obtains and refreshes a token against the emulator`() {
    val issued = AtomicInteger(0)
    val cache =
        RefreshingTokenCache({
          val n = issued.incrementAndGet()
          BrokeredToken(
              accessToken = "ya29.n$n",
              expiresAt = Instant.now().plusSeconds(3600),
              serviceAccountEmail = "target@my-project.iam.gserviceaccount.com",
          )
        })

    MetadataEmulator(
            cache,
            InetSocketAddress(InetAddress.getLoopbackAddress(), 0),
            { it.isLoopbackAddress },
        )
        .use { emu ->
          emu.start()
          val transport = RewritingTransport(emu.hostPort)
          val credentials =
              ComputeEngineCredentials.newBuilder()
                  .setHttpTransportFactory(HttpTransportFactory { transport })
                  .build()

          credentials.refresh()
          assertEquals("ya29.n1", credentials.accessToken.tokenValue)

          // Simulate the cached token nearing expiry: the client asks again and must get the new
          // one.
          cache.forceExpire()
          credentials.refresh()
          assertEquals("ya29.n2", credentials.accessToken.tokenValue)
        }
  }
}
