package software.medusa.workload.runtime

import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SecretBrokerAuthTest {
  @Test
  fun `authorizationHeader is Bearer workerId dot secret`() {
    val auth = SecretBrokerAuth("worker-1", "s3cr3t")
    assertEquals("Bearer worker-1.s3cr3t", auth.authorizationHeader())
  }
}

/** A minimal stand-in for the GCE metadata server's identity endpoint. */
private class FakeMetadataServer(
    private val respond: (audience: String?, flavorHeaderPresent: Boolean) -> Pair<Int, String>
) : AutoCloseable {
  private val server: HttpServer =
      HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/computeMetadata/v1/instance/service-accounts/default/identity") { exchange
          ->
          val audience = exchange.requestURI.rawQuery?.removePrefix("audience=")
          val flavorPresent =
              exchange.requestHeaders
                  .getFirst("Metadata-Flavor")
                  .equals("Google", ignoreCase = true)
          val (status, body) = respond(audience, flavorPresent)
          val bytes = body.toByteArray()
          exchange.sendResponseHeaders(status, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        }
        start()
      }

  val hostPort: String
    get() = "${server.address.hostString}:${server.address.port}"

  override fun close() = server.stop(0)
}

class GceMetadataBrokerAuthTest {
  @Test
  fun `fetches an identity token for the given audience and sends it as a Bearer header`() {
    FakeMetadataServer { _, _ -> 200 to "the-id-token" }
        .use { fake ->
          val auth =
              GceMetadataBrokerAuth(
                  "https://api.example.com",
                  metadataHost = "http://${fake.hostPort}",
              )
          assertEquals("Bearer the-id-token", auth.authorizationHeader())
        }
  }

  @Test
  fun `URL-encodes the audience and sends the Metadata-Flavor header`() {
    var seenAudience: String? = null
    var seenFlavor = false
    FakeMetadataServer { audience, flavorPresent ->
          seenAudience = audience
          seenFlavor = flavorPresent
          200 to "tok"
        }
        .use { fake ->
          val auth =
              GceMetadataBrokerAuth(
                  "https://api.example.com/x y",
                  metadataHost = "http://${fake.hostPort}",
              )
          auth.authorizationHeader()
        }
    assertEquals("https%3A%2F%2Fapi.example.com%2Fx+y", seenAudience)
    assertTrue(seenFlavor)
  }

  @Test
  fun `throws when the metadata server does not answer 200`() {
    FakeMetadataServer { _, _ -> 404 to "not found" }
        .use { fake ->
          val auth =
              GceMetadataBrokerAuth(
                  "https://api.example.com",
                  metadataHost = "http://${fake.hostPort}",
              )
          assertFailsWith<IllegalStateException> { auth.authorizationHeader() }
        }
  }

  @Test
  fun `throws when the metadata server returns an empty token`() {
    FakeMetadataServer { _, _ -> 200 to "" }
        .use { fake ->
          val auth =
              GceMetadataBrokerAuth(
                  "https://api.example.com",
                  metadataHost = "http://${fake.hostPort}",
              )
          assertFailsWith<IllegalStateException> { auth.authorizationHeader() }
        }
  }
}

class NodeKindDispatchTest {
  @Test
  fun `SECRET builds a SecretBrokerAuth from the worker credential`() {
    val auth =
        brokerAuthFor(
            NodeKind.SECRET,
            audience = "https://api.example.com",
            workerId = "worker-1",
            workerSecret = "s3cr3t",
        )
    assertEquals("Bearer worker-1.s3cr3t", auth.authorizationHeader())
  }

  @Test
  fun `GCE_METADATA builds a GceMetadataBrokerAuth ignoring worker credentials`() {
    val auth = brokerAuthFor(NodeKind.GCE_METADATA, audience = "https://api.example.com")
    assertTrue(auth is GceMetadataBrokerAuth)
  }

  @Test
  fun `SECRET without a worker credential fails fast`() {
    assertFailsWith<IllegalStateException> {
      brokerAuthFor(NodeKind.SECRET, audience = "https://api.example.com")
    }
  }
}
