package software.medusa.workload.docker

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.QueryParams
import kotlin.time.Duration
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

/**
 * Container lifecycle endpoints — the M3-02 surface. Reached as `connector.containers`.
 *
 * The methods here cover exactly what a workload run needs: create a container from an image with
 * an environment and identifying labels, start it, block for its exit code, stop it politely (with
 * a SIGKILL backstop), and remove it — plus label-filtered `list` and a minimal `inspect`.
 *
 * **Env never touches a command line.** `create` passes the environment in the JSON request body
 * only, so values (which may be secrets) never appear in `argv` or a `ps` listing.
 *
 * **AutoRemove defaults on.** Unless told otherwise, a created container reaps its own filesystem
 * on exit, so repeated runs don't accumulate dead containers.
 */
class ContainerApi internal constructor(private val engine: DockerEngine) {

  /**
   * `POST /containers/create`. [env] entries are `KEY=VALUE` strings passed in the body only.
   * [labels] identify the container for later `list`/cleanup (workload uses `ms-workload.*`).
   * [autoRemove] (default `true`) sets `HostConfig.AutoRemove` so the container is reaped on exit.
   * [extraHosts] maps to `--add-host` (`hostname:ip`, or `hostname:host-gateway`) and [networkMode]
   * to `--network` — how `workload run` publishes `metadata.google.internal` and joins the
   * container to its per-run bridge (M4-B2). Both omitted from the body when null/empty.
   */
  suspend fun create(
      image: String,
      cmd: List<String>? = null,
      env: List<String> = emptyList(),
      labels: Map<String, String> = emptyMap(),
      autoRemove: Boolean = true,
      tty: Boolean = false,
      name: String? = null,
      extraHosts: List<String> = emptyList(),
      networkMode: String? = null,
  ): ContainerCreateResponse {
    val body =
        engine.json.encodeToString(
            ContainerCreateRequest(
                image = image,
                cmd = cmd,
                env = env,
                labels = labels,
                tty = tty,
                hostConfig =
                    HostConfig(
                        autoRemove = autoRemove,
                        extraHosts = extraHosts.ifEmpty { null },
                        networkMode = networkMode,
                    ),
            )
        )
    val query = if (name != null) "?" + queryOf("name" to name) else ""
    val response =
        engine.exchange(
            HttpMethod.POST,
            engine.versionedPath("/containers/create") + query,
            jsonBody = body,
        )
    response.ensureSuccess(engine.json)
    return response.decodeBody(engine.json, "container create")
  }

  /**
   * `POST /containers/{id}/start`. Idempotent-ish: a 304 (already started) is treated as success.
   */
  suspend fun start(id: String) {
    val response = engine.exchange(HttpMethod.POST, engine.versionedPath("/containers/$id/start"))
    // 304 Not Modified == "already started"; the daemon uses it as a success signal here.
    if (response.status().code() == HTTP_NOT_MODIFIED) {
      return
    }
    response.ensureSuccess(engine.json)
  }

  /**
   * `POST /containers/{id}/wait` — blocks until the container exits, then returns its exit code.
   */
  suspend fun wait(id: String): ContainerWaitResult {
    val response = engine.exchange(HttpMethod.POST, engine.versionedPath("/containers/$id/wait"))
    response.ensureSuccess(engine.json)
    return response.decodeBody(engine.json, "container wait")
  }

  /**
   * `POST /containers/{id}/stop` — the one-call graceful-stop primitive. The daemon sends SIGTERM,
   * waits [timeout], then SIGKILLs if the process is still alive. A container that ignores SIGTERM
   * thus exits ~[timeout] later with code 137 (128 + SIGKILL).
   */
  suspend fun stop(id: String, timeout: Duration) {
    val query = queryOf("t" to timeout.inWholeSeconds.toString())
    val response =
        engine.exchange(HttpMethod.POST, engine.versionedPath("/containers/$id/stop") + "?" + query)
    response.ensureSuccess(engine.json)
  }

  /**
   * `DELETE /containers/{id}`. [force] kills a running container first; [removeVolumes] (default
   * `true`) also removes anonymous volumes the container created.
   */
  suspend fun remove(id: String, force: Boolean = false, removeVolumes: Boolean = true) {
    val query = queryOf("force" to force.toString(), "v" to removeVolumes.toString())
    val response =
        engine.exchange(HttpMethod.DELETE, engine.versionedPath("/containers/$id") + "?" + query)
    response.ensureSuccess(engine.json)
  }

  /**
   * `GET /containers/json`. [labels] become a Docker `label` filter (`key=value`), so workload can
   * enumerate exactly the containers it owns. [all] (default `true`) includes stopped containers.
   */
  suspend fun list(
      labels: Map<String, String> = emptyMap(),
      labelKeys: List<String> = emptyList(),
      all: Boolean = true,
  ): List<ContainerSummary> {
    val params = QueryParams.builder().add("all", all.toString())
    // Docker's label filter takes both `key=value` and bare `key` (existence) terms. The bare form
    // is what `workload ps` needs: find everything *we* own, whatever its profile.
    val labelTerms = labels.map { (k, v) -> "$k=$v" } + labelKeys
    if (labelTerms.isNotEmpty()) {
      val filters =
          engine.json.encodeToString(
              MapSerializer(String.serializer(), ListSerializer(String.serializer())),
              mapOf("label" to labelTerms),
          )
      params.add("filters", filters)
    }
    val response =
        engine.exchange(
            HttpMethod.GET,
            engine.versionedPath("/containers/json") + "?" + params.build().toQueryString(),
        )
    response.ensureSuccess(engine.json)
    return response.decodeBody(engine.json, "container list")
  }

  /**
   * `POST /containers/prune` filtered to [labels]/[labelKeys] — removes **stopped** containers that
   * carry every given label term. Prune never touches a running container, so this is safe to call
   * mid-run: workload uses it to reap its own exited containers (`label=ms-workload.profile`)
   * without disturbing a live run, its own or another's. Returns the ids removed and bytes freed.
   */
  suspend fun prune(
      labels: Map<String, String> = emptyMap(),
      labelKeys: List<String> = emptyList(),
  ): ContainersPruneResult {
    val labelTerms = labels.map { (k, v) -> "$k=$v" } + labelKeys
    val params = QueryParams.builder()
    if (labelTerms.isNotEmpty()) {
      val filters =
          engine.json.encodeToString(
              MapSerializer(String.serializer(), ListSerializer(String.serializer())),
              mapOf("label" to labelTerms),
          )
      params.add("filters", filters)
    }
    val query = params.build().toQueryString()
    val path = engine.versionedPath("/containers/prune") + if (query.isNotEmpty()) "?$query" else ""
    val response = engine.exchange(HttpMethod.POST, path)
    response.ensureSuccess(engine.json)
    return response.decodeBody(engine.json, "containers prune")
  }

  /** `GET /containers/{id}/json` — minimal typed projection (state, exit code, config/labels). */
  suspend fun inspect(id: String): ContainerInspect {
    val response = engine.exchange(HttpMethod.GET, engine.versionedPath("/containers/$id/json"))
    response.ensureSuccess(engine.json)
    return response.decodeBody(engine.json, "container inspect")
  }

  private fun queryOf(vararg pairs: Pair<String, String>): String {
    val builder = QueryParams.builder()
    for ((k, v) in pairs) {
      builder.add(k, v)
    }
    return builder.build().toQueryString()
  }

  private companion object {
    const val HTTP_NOT_MODIFIED = 304
  }
}
