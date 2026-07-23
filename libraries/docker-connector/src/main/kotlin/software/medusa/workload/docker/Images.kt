package software.medusa.workload.docker

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.QueryParams
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Byte counts for a layer in flight. Absent/zero for status-only records. */
@kotlinx.serialization.Serializable
data class ProgressDetail(
    @kotlinx.serialization.SerialName("current") val current: Long? = null,
    @kotlinx.serialization.SerialName("total") val total: Long? = null,
)

/**
 * One record from a pull's progress stream: `status` is the human phrase ("Downloading", "Pull
 * complete"), [id] the layer it concerns (absent for whole-image records).
 */
@kotlinx.serialization.Serializable
data class PullProgress(
    @kotlinx.serialization.SerialName("status") val status: String? = null,
    @kotlinx.serialization.SerialName("id") val id: String? = null,
    @kotlinx.serialization.SerialName("progressDetail") val progressDetail: ProgressDetail? = null,
)

/** The in-stream failure record the daemon emits *after* a 200. See [DockerPullException]. */
@kotlinx.serialization.Serializable
internal data class PullErrorRecord(
    @kotlinx.serialization.SerialName("error") val error: String? = null,
    @kotlinx.serialization.SerialName("errorDetail") val errorDetail: PullErrorDetail? = null,
)

@kotlinx.serialization.Serializable
internal data class PullErrorDetail(
    @kotlinx.serialization.SerialName("message") val message: String? = null,
)

/** Minimal typed projection of `GET /images/{name}/json`. */
@kotlinx.serialization.Serializable
data class ImageInspect(
    @kotlinx.serialization.SerialName("Id") val id: String,
    @kotlinx.serialization.SerialName("RepoTags") val repoTags: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("RepoDigests") val repoDigests: List<String> = emptyList(),
    @kotlinx.serialization.SerialName("Architecture") val architecture: String? = null,
    @kotlinx.serialization.SerialName("Os") val os: String? = null,
    @kotlinx.serialization.SerialName("Size") val size: Long = 0,
)

/**
 * Splits an image reference into the repository and the tag/digest to address it by. Mirrors
 * docker-py's `parse_repository_tag`: a `@` wins (digest), otherwise a `:` in the final path
 * segment is a tag (a `:` before a `/` is a registry port). `null` means the daemon's default
 * (`latest`).
 */
internal fun splitRepositoryAndReference(ref: String): Pair<String, String?> {
  val trimmed = ref.trim()
  val at = trimmed.lastIndexOf('@')
  if (at > 0) return trimmed.substring(0, at) to trimmed.substring(at + 1)
  val colon = trimmed.lastIndexOf(':')
  val slash = trimmed.lastIndexOf('/')
  if (colon > slash) return trimmed.substring(0, colon) to trimmed.substring(colon + 1)
  return trimmed to null
}

/** The registry host of a reference — the first path segment, when it looks like a host. */
internal fun registryOf(ref: String): String? {
  val first = ref.trim().substringBefore('/', missingDelimiterValue = "")
  return if ('.' in first || ':' in first || first == "localhost") first else null
}

/**
 * Image endpoints — reached as `connector.images`.
 *
 * [pull] is the reason this exists: it replaces shelling out to `docker pull`, while still riding
 * this host's own Docker sign-in via [DockerAuthResolver].
 */
class ImageApi
internal constructor(
    private val engine: DockerEngine,
    private val authResolver: DockerAuthResolver,
) {

  /**
   * `POST /images/create` — pulls [ref] (a tag or, preferably, a `repo@sha256:...` digest) and
   * emits the daemon's progress records as they arrive.
   *
   * **Errors.** A pull that fails still returns HTTP 200; the daemon reports the failure as an
   * `error` record inside the stream. This flow throws [DockerPullException] the moment it sees
   * one, so a caller that simply collects the flow cannot mistake a failed pull for a successful
   * one.
   *
   * [auth] overrides credential lookup; when null, credentials are resolved from
   * `~/.docker/config.json` for [ref]'s registry (credHelpers → credsStore → auths → anonymous).
   */
  fun pull(ref: String, auth: RegistryAuth? = null): Flow<PullProgress> = flow {
    val (repository, reference) = splitRepositoryAndReference(ref)
    val params = QueryParams.builder().add("fromImage", repository)
    if (reference != null) {
      params.add("tag", reference)
    }
    val path = engine.versionedPath("/images/create") + "?" + params.build().toQueryString()

    val resolved = auth ?: registryOf(ref)?.let { authResolver.resolve(it) }
    val headers = resolved?.let { mapOf("X-Registry-Auth" to it.toHeaderValue()) } ?: emptyMap()

    val splitter = NdjsonSplitter()
    engine.streamBytes(HttpMethod.POST, path, headers).collect { chunk ->
      for (line in splitter.feed(chunk)) {
        emitRecord(line)
      }
    }
    for (line in splitter.flush()) {
      emitRecord(line)
    }
  }

  /**
   * `GET /images/{ref}/json`. Throws [DockerApiException] with 404 when the image isn't present.
   */
  suspend fun inspect(ref: String): ImageInspect {
    val response = engine.exchange(HttpMethod.GET, engine.versionedPath("/images/$ref/json"))
    response.ensureSuccess(engine.json)
    return response.decodeBody(engine.json, "image inspect")
  }

  /**
   * `GET /images/json` — every top-level local image (intermediate layers excluded, as the daemon
   * defaults). Workload's ownership-scoped GC (M7-00) lists these to find superseded revisions of a
   * repository via their [ImageSummary.repoDigests]; there is no server-side "which images does
   * workload own" filter, so ownership is decided client-side off the repo the digest names.
   */
  suspend fun list(): List<ImageSummary> {
    val response = engine.exchange(HttpMethod.GET, engine.versionedPath("/images/json"))
    response.ensureSuccess(engine.json)
    return response.decodeBody(engine.json, "image list")
  }

  /**
   * `POST /images/{source}/tag` — adds a `[repo]:[tag]` reference to an existing local image
   * [source] (an id or an existing ref). A general Engine primitive; workload's own run path
   * doesn't tag, but the GC contract tests use it to stage several "revisions" of a repository on a
   * real daemon without a registry.
   */
  suspend fun tag(source: String, repo: String, tag: String) {
    val query = QueryParams.builder().add("repo", repo).add("tag", tag).build().toQueryString()
    val response =
        engine.exchange(HttpMethod.POST, engine.versionedPath("/images/$source/tag") + "?" + query)
    response.ensureSuccess(engine.json)
  }

  /**
   * `DELETE /images/{ref}` — [ref] is an image id (`sha256:...`), a `repo:tag`, or a `repo@digest`.
   * Returns the daemon's untag/delete records.
   *
   * [force] defaults **false**, and workload's GC keeps it that way: the daemon refuses to delete
   * an image still referenced by a container (or referenced under multiple repos) without force,
   * answering `409 Conflict`. That refusal is exactly the "still in use — keep it" signal the GC
   * wants; forcing would tear an image out from under a running container. Callers treat a
   * [DockerApiException] here as "kept", never as a reason to retry with force.
   */
  suspend fun remove(ref: String, force: Boolean = false): List<ImageDeleteRecord> {
    val query = QueryParams.builder().add("force", force.toString()).build().toQueryString()
    val response =
        engine.exchange(HttpMethod.DELETE, engine.versionedPath("/images/$ref") + "?" + query)
    response.ensureSuccess(engine.json)
    return response.decodeBody(engine.json, "image remove")
  }

  private suspend fun kotlinx.coroutines.flow.FlowCollector<PullProgress>.emitRecord(line: String) {
    val error = runCatching { engine.json.decodeFromString<PullErrorRecord>(line) }.getOrNull()
    if (error?.error != null) {
      val detail = error.errorDetail?.message?.takeIf { it != error.error }
      throw DockerPullException(error.error, detail)
    }
    val progress =
        try {
          engine.json.decodeFromString<PullProgress>(line)
        } catch (e: Exception) {
          throw DockerProtocolException("Malformed pull progress record: $line", e)
        }
    emit(progress)
  }
}
