package software.medusa.workload.docker

import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.QueryParams
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString

/**
 * Network endpoints — the M4-B2 surface. Reached as `connector.networks`.
 *
 * Exactly the lifecycle `workload run` needs for a per-run bridge: create a labeled bridge, inspect
 * it for its gateway IP (where the metadata emulator binds), remove it on teardown, and list
 * `ms-workload.*` networks so `workload ps --reap` can sweep any a hard-killed CLI orphaned — no
 * connect/disconnect/prune surface beyond that.
 */
class NetworkApi internal constructor(private val engine: DockerEngine) {

  /**
   * `POST /networks/create` — a bridge network named [name], tagged with [labels] (workload uses
   * `ms-workload.*` so [list] can find it later). Bridge is the only driver we use.
   */
  suspend fun create(
      name: String,
      labels: Map<String, String> = emptyMap(),
      driver: String = "bridge",
  ): NetworkCreateResponse {
    val body =
        engine.json.encodeToString(
            NetworkCreateRequest(name = name, driver = driver, labels = labels)
        )
    val response =
        engine.exchange(
            HttpMethod.POST,
            engine.versionedPath("/networks/create"),
            jsonBody = body,
        )
    response.ensureSuccess(engine.json)
    return response.decodeBody(engine.json, "network create")
  }

  /** `GET /networks/{id}` — typed projection (id/name/driver/IPAM gateway/labels). */
  suspend fun inspect(id: String): NetworkInspect {
    val response = engine.exchange(HttpMethod.GET, engine.versionedPath("/networks/$id"))
    response.ensureSuccess(engine.json)
    return response.decodeBody(engine.json, "network inspect")
  }

  /**
   * `DELETE /networks/{id}`. Fails at the daemon if a container is still attached — callers reap
   * containers first. A 204 is success.
   */
  suspend fun remove(id: String) {
    val response = engine.exchange(HttpMethod.DELETE, engine.versionedPath("/networks/$id"))
    response.ensureSuccess(engine.json)
  }

  /**
   * `GET /networks`. [labels] become `key=value` filter terms and [labelKeys] bare-key (existence)
   * terms — the latter is what `workload ps --reap` needs: every network we own, whatever the run.
   */
  suspend fun list(
      labels: Map<String, String> = emptyMap(),
      labelKeys: List<String> = emptyList(),
  ): List<NetworkSummary> {
    val params = QueryParams.builder()
    val labelTerms = labels.map { (k, v) -> "$k=$v" } + labelKeys
    if (labelTerms.isNotEmpty()) {
      val filters =
          engine.json.encodeToString(
              MapSerializer(String.serializer(), ListSerializer(String.serializer())),
              mapOf("label" to labelTerms),
          )
      params.add("filters", filters)
    }
    val query = params.build().toQueryString()
    val response =
        engine.exchange(
            HttpMethod.GET,
            engine.versionedPath("/networks") + if (query.isEmpty()) "" else "?$query",
        )
    response.ensureSuccess(engine.json)
    return response.decodeBody(engine.json, "network list")
  }
}
