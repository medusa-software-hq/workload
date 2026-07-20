package software.medusa.workload.server

import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server

/**
 * Mounts a single worker-plane [service] on a bare [path] on a fresh, ephemeral-port server, with
 * no decorators around it.
 *
 * The production plane always wraps these services in the `v2WorkerCredentialDrop` / registration
 * parse-and-drop decorators (see [buildServer]); that noise-shedding + 401→404-unification
 * behaviour is covered on its own by [WorkerPlaneV2UnprobeableTest]. These service-level tests
 * instead assert the handler's own status semantics (200 vs 400 vs 401 vs 403 with the right error
 * body), which need the response *un*rewritten — so they mount it bare.
 */
internal fun buildWorkerServiceTestServer(path: String, service: HttpService): Server =
    Server.builder()
        .apply {
          http(0)
          service(path, service)
        }
        .build()
