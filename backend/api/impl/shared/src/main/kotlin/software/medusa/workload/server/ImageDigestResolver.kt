package software.medusa.workload.server

import com.google.api.gax.rpc.ApiException
import com.google.cloud.iam.credentials.v1.GenerateAccessTokenRequest
import com.google.cloud.iam.credentials.v1.IamCredentialsClient
import com.google.cloud.iam.credentials.v1.ServiceAccountName
import com.google.protobuf.Duration
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration as JavaDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private const val resolverTokenLifetimeSeconds = 60L
private const val resolverCloudPlatformScope = "https://www.googleapis.com/auth/cloud-platform"
private const val manifestRequestTimeoutSeconds = 10L

// Media types we'll accept back for a manifest HEAD — v2 schema 2, manifest lists, and the OCI
// equivalents, so multi-arch images (an index) resolve just like single-arch ones.
private val manifestAcceptTypes =
    listOf(
        "application/vnd.docker.distribution.manifest.v2+json",
        "application/vnd.docker.distribution.manifest.list.v2+json",
        "application/vnd.oci.image.manifest.v1+json",
        "application/vnd.oci.image.index.v1+json",
    )

private val resolverLogger = LoggerFactory.getLogger(GcpImageDigestResolver::class.java)

/**
 * Outcome of resolving an image tag to a digest. [detail] is a safe-to-log diagnostic (never a
 * secret). Mirrors [VerificationResult]'s three-way shape:
 * - [ImageStatus.RESOLVED] with a non-null [digest].
 * - [ImageStatus.UNRESOLVABLE] — a permanent denial (registry 403/404, or a malformed ref).
 * - [ImageStatus.UNDETERMINED] — couldn't complete (timeout, 5xx, transport error); retried on the
 *   next create/update/verify rather than recorded as a verdict.
 */
data class ImageResolution(
    val status: ImageStatus,
    val digest: String? = null,
    val detail: String? = null,
)

/**
 * Resolves a revision's image tag to an immutable digest at creation time, authenticating as the
 * revision's target SA (the same impersonation identity used for secret verification). Reads the
 * registry's `Docker-Content-Digest` off a manifest `HEAD`. Never returns a usable token.
 */
interface ImageDigestResolver {
  suspend fun resolve(targetServiceAccount: String, imageRef: String): ImageResolution
}

/**
 * A parsed image reference: `host/repository:tag` (or `…@sha256:…`). [reference] is the tag or
 * digest to address the manifest by.
 */
internal data class ParsedImageRef(
    val host: String,
    val repository: String,
    val reference: String,
) {
  val manifestUrl: String
    get() = "https://$host/v2/$repository/manifests/$reference"
}

/**
 * Whether [host] is a Google-operated container registry — Artifact Registry (`*.pkg.dev`) or the
 * legacy Container Registry (`gcr.io`, `*.gcr.io`).
 *
 * **A security boundary, not a convenience check.** Resolving a digest sends an access token
 * impersonating the revision's target SA to this host, and the host comes from the profile's image
 * ref. Without this, anyone who can set a profile's image could point it at a host they control and
 * be handed a live token for that service account — turning "can edit a profile" into "can act as
 * any SA that opted into impersonation", which is precisely the trust boundary the impersonation
 * opt-in exists to protect. The worker applies the same rule before sending its brokered token.
 *
 * Deliberately strict: the port is *not* stripped (`us-docker.pkg.dev:8080` isn't Google's), and
 * only a true dot-suffix matches, so `evil-pkg.dev` and `us-docker.pkg.dev.evil.com` are rejected.
 */
internal fun isGoogleRegistryHost(host: String): Boolean {
  val normalized = host.lowercase()
  return normalized == "gcr.io" || normalized.endsWith(".gcr.io") || normalized.endsWith(".pkg.dev")
}

/**
 * Splits a Docker image reference into host/repository/reference. Requires a registry host (a first
 * segment containing a `.` or `:` — Artifact Registry is always `*.pkg.dev`), so a bare
 * `busybox:latest` (implicit Docker Hub) is rejected: workload images live in Artifact Registry.
 * Returns null when unparseable.
 */
internal fun parseImageRef(imageRef: String): ParsedImageRef? {
  val trimmed = imageRef.trim()
  val firstSlash = trimmed.indexOf('/')
  if (firstSlash <= 0) return null
  val host = trimmed.substring(0, firstSlash)
  if ('.' !in host && ':' !in host) return null
  val remainder = trimmed.substring(firstSlash + 1)
  if (remainder.isEmpty()) return null

  // A digest reference (`repo@sha256:...`) takes precedence over a tag.
  val atIndex = remainder.indexOf('@')
  if (atIndex > 0) {
    val repository = remainder.substring(0, atIndex)
    val reference = remainder.substring(atIndex + 1)
    if (repository.isEmpty() || reference.isEmpty()) return null
    return ParsedImageRef(host, repository, reference)
  }

  // Otherwise a tag is the segment after the last `:` — but only if that `:` is in the final path
  // segment (a `:` before a `/` would be a port, which can't happen here since host is stripped).
  val lastColon = remainder.lastIndexOf(':')
  val lastSlash = remainder.lastIndexOf('/')
  return if (lastColon > lastSlash) {
    val repository = remainder.substring(0, lastColon)
    val tag = remainder.substring(lastColon + 1)
    if (repository.isEmpty() || tag.isEmpty()) null else ParsedImageRef(host, repository, tag)
  } else {
    ParsedImageRef(host, remainder, "latest")
  }
}

/**
 * Mints a short-lived access token impersonating a target SA. Injectable so the host-guard tests can
 * prove no token is ever minted for a non-Google registry.
 */
internal fun interface ImpersonatedTokenMinter {
  fun mint(targetServiceAccount: String): String
}

/** Mints a token for [targetServiceAccount] and asks the registry for the manifest digest. */
class GcpImageDigestResolver
internal constructor(
    private val minter: ImpersonatedTokenMinter,
    private val httpClient: HttpClient,
) : ImageDigestResolver {
  constructor(
      iamCredentialsClient: IamCredentialsClient,
      httpClient: HttpClient = HttpClient.newHttpClient(),
  ) : this(
      ImpersonatedTokenMinter { targetServiceAccount ->
        iamCredentialsClient
            .generateAccessToken(
                GenerateAccessTokenRequest.newBuilder()
                    .setName(ServiceAccountName.of("-", targetServiceAccount).toString())
                    .addScope(resolverCloudPlatformScope)
                    .setLifetime(
                        Duration.newBuilder().setSeconds(resolverTokenLifetimeSeconds).build()
                    )
                    .build()
            )
            .accessToken
      },
      httpClient,
  )

  override suspend fun resolve(
      targetServiceAccount: String,
      imageRef: String,
  ): ImageResolution =
      withContext(Dispatchers.IO) {
        val parsed =
            parseImageRef(imageRef)
                ?: return@withContext ImageResolution(
                    ImageStatus.UNRESOLVABLE,
                    detail = "malformed image reference: '$imageRef'",
                )

        // Refuse *before* minting: we will not hand an impersonated token to a host that isn't
        // Google's. `requireValidImageRef` rejects these at create/update, so reaching here means a
        // row predating that check (or a bypass) — either way, resolution fails rather than leaks.
        if (!isGoogleRegistryHost(parsed.host)) {
          resolverLogger.error(
              "refusing to resolve {} — {} is not a Google container registry; no token was minted",
              imageRef,
              parsed.host,
          )
          return@withContext ImageResolution(
              ImageStatus.UNRESOLVABLE,
              detail =
                  "'${parsed.host}' is not a Google container registry (Artifact Registry or GCR). " +
                      "Workload only resolves — and only ever sends credentials to — Google registries.",
          )
        }

        val token =
            try {
              minter.mint(targetServiceAccount)
            } catch (e: ApiException) {
              // Denial to *mint* means the broker can't impersonate this SA at all — a binding
              // problem, surfaced as verification elsewhere; here it just blocks resolution.
              if (isDenialCode(e.statusCode.code)) {
                return@withContext ImageResolution(
                    ImageStatus.UNRESOLVABLE,
                    detail = "cannot impersonate $targetServiceAccount: ${e.describeError()}",
                )
              }
              return@withContext undetermined(targetServiceAccount, imageRef, e)
            } catch (e: Exception) {
              return@withContext undetermined(targetServiceAccount, imageRef, e)
            }

        try {
          val response = headManifest(parsed, token)
          mapResponse(parsed, response)
        } catch (e: Exception) {
          undetermined(targetServiceAccount, imageRef, e)
        }
      }

  private fun mapResponse(
      parsed: ParsedImageRef,
      response: HttpResponse<Void>,
  ): ImageResolution =
      when (val code = response.statusCode()) {
        in SUCCESS_STATUS -> {
          val digest = response.headers().firstValue("Docker-Content-Digest").orElse(null)
          if (digest.isNullOrBlank()) {
            // 2xx but no digest header — shouldn't happen for a manifest, but don't invent one.
            ImageResolution(
                ImageStatus.UNDETERMINED,
                detail = "registry returned ${code} without a Docker-Content-Digest header",
            )
          } else {
            ImageResolution(ImageStatus.RESOLVED, digest = digest)
          }
        }
        // Not authorized, or the tag/repo doesn't exist — a genuine, permanent verdict.
        in DENIAL_STATUS ->
            ImageResolution(
                ImageStatus.UNRESOLVABLE,
                detail = "registry returned $code for ${parsed.manifestUrl}",
            )
        // 5xx / anything else: couldn't determine; retry later rather than flag permanently.
        else -> {
          resolverLogger.error(
              "manifest HEAD for {} returned {} — recording UNDETERMINED",
              parsed.manifestUrl,
              code,
          )
          ImageResolution(
              ImageStatus.UNDETERMINED,
              detail = "registry returned $code for ${parsed.manifestUrl}",
          )
        }
      }

  private fun headManifest(parsed: ParsedImageRef, token: String): HttpResponse<Void> {
    val request =
        HttpRequest.newBuilder(URI.create(parsed.manifestUrl))
            .method("HEAD", HttpRequest.BodyPublishers.noBody())
            .header("Authorization", "Bearer $token")
            .header("Accept", manifestAcceptTypes.joinToString(","))
            .timeout(JavaDuration.ofSeconds(manifestRequestTimeoutSeconds))
            .build()
    return httpClient.send(request, HttpResponse.BodyHandlers.discarding())
  }


  private fun undetermined(
      targetServiceAccount: String,
      imageRef: String,
      e: Exception,
  ): ImageResolution {
    resolverLogger.error(
        "image digest resolution could not be completed for {} ({}) — recording UNDETERMINED",
        imageRef,
        targetServiceAccount,
        e,
    )
    return ImageResolution(
        ImageStatus.UNDETERMINED,
        detail = "resolution error: ${e.describeError()}",
    )
  }

  private fun Exception.describeError(): String = "${this::class.simpleName}: $message"

  private companion object {
    val SUCCESS_STATUS = 200..299
    val DENIAL_STATUS = setOf(401, 403, 404)
  }
}

/** Resolves every image to a fixed fake digest — for local dev, where there's no real registry. */
object AlwaysResolvedImageDigestResolver : ImageDigestResolver {
  override suspend fun resolve(targetServiceAccount: String, imageRef: String): ImageResolution =
      ImageResolution(
          ImageStatus.RESOLVED,
          digest = "sha256:0000000000000000000000000000000000000000000000000000000000000000",
      )
}
