package software.medusa.workload.server

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.common.HttpStatus
import com.linecorp.armeria.common.MediaType
import com.linecorp.armeria.common.ResponseHeaders
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.cors.CorsService
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.healthcheck.HealthCheckService
import com.linecorp.armeria.server.throttling.ThrottlingService
import com.linecorp.armeria.server.throttling.ThrottlingStrategy

private const val workerTokenBrokerQps = 5.0

/** A worker-plane HTTP route: an exact path (relative to the worker prefix) plus method. */
internal data class WorkerPlaneRoute(
    val method: HttpMethod,
    val path: String,
)

// A fresh, headers-only HttpResponse per call: HttpResponse.of(HttpStatus) synthesizes a text
// body ("404 Not Found"), which fails the "no body" requirement; HttpResponse instances are also
// single-use streams and must not be shared across requests.
private fun bareNotFound(): HttpResponse = HttpResponse.of(ResponseHeaders.of(HttpStatus.NOT_FOUND))

/**
 * Wraps every route so worker-plane requests (path contains `/worker/v1/`) are gated by
 * [workerApiPathPrefix] before they reach any service. A wrong/missing prefix is internet
 * background noise (bot scanners): it gets a bare 404 (no body) counted in
 * [RejectedWorkerRequestCounter], never an audit-log line. Everything else (gRPC, `/health`) passes
 * through untouched — the prefix is anti-noise hygiene, not a security boundary. A correctly
 * prefixed request for a path/method not in [workerPlaneRoutes] is a plain 404, not counted as
 * rejected noise (it's not a wrong-prefix bot hit).
 */
private fun workerApiPathPrefixDecorator(
    workerApiPathPrefix: String,
    workerPlaneRoutes: Map<WorkerPlaneRoute, HttpService>,
): DecoratingHttpServiceFunction = DecoratingHttpServiceFunction { delegate, ctx, req ->
  when (val match = matchWorkerApiPath(ctx.path(), workerApiPathPrefix)) {
    WorkerApiPathMatch.NotWorkerPath -> delegate.serve(ctx, req)
    WorkerApiPathMatch.PrefixMismatch -> {
      RejectedWorkerRequestCounter.increment()
      bareNotFound()
    }
    is WorkerApiPathMatch.Matched -> {
      val route = WorkerPlaneRoute(ctx.method(), match.remainder)
      workerPlaneRoutes[route]?.serve(ctx, req) ?: bareNotFound()
    }
  }
}

fun buildServer(
    originRegex: String,
    port: Int,
    workerApiPathPrefix: String,
    auth: DecoratingHttpServiceFunction,
    counterStore: WorkloadStore,
    fleetStore: FleetStore,
    impersonationVerifier: ImpersonationVerifier,
    workerTokenBroker: HttpService? = null,
    registrationService: HttpService? = null,
    selfStatusService: HttpService? = null,
): Server {
  val cors =
      CorsService.builderForOriginRegex(originRegex)
          .apply {
            allowRequestMethods(HttpMethod.POST, HttpMethod.OPTIONS)
            allowRequestHeaders(
                HttpHeaderNames.AUTHORIZATION,
                HttpHeaderNames.CONTENT_TYPE,
                GrpcHeaderNames.X_GRPC_WEB,
                GrpcHeaderNames.X_USER_AGENT,
                GrpcHeaderNames.GRPC_TIMEOUT,
                GrpcHeaderNames.CONNECT_PROTOCOL_VERSION,
                GrpcHeaderNames.CONNECT_TIMEOUT_MS,
            )
            exposeHeaders(
                GrpcHeaderNames.GRPC_STATUS,
                GrpcHeaderNames.GRPC_MESSAGE,
                HttpHeaderNames.CONTENT_TYPE,
            )
          }
          .newDecorator()

  val grpcService =
      GrpcService.builder()
          .apply {
            addService(WorkloadServiceImpl(counterStore))
            addService(FleetServiceImpl(fleetStore, impersonationVerifier))
            enableUnframedRequests(true)
          }
          .build()

  val throttledWorkerTokenBroker =
      workerTokenBroker?.decorate(
          ThrottlingService.newDecorator(ThrottlingStrategy.rateLimiting(workerTokenBrokerQps))
      )

  val workerPlaneRoutes =
      buildMap<WorkerPlaneRoute, HttpService> {
        throttledWorkerTokenBroker?.let {
          put(WorkerPlaneRoute(HttpMethod.POST, "/worker/v1/token"), it)
        }
        registrationService?.let {
          put(WorkerPlaneRoute(HttpMethod.POST, "/worker/v1/registrations"), it)
        }
        selfStatusService?.let {
          put(WorkerPlaneRoute(HttpMethod.GET, "/worker/v1/registrations/self"), it)
        }
      }

  return Server.builder()
      .apply {
        http(port)

        // Health check is unauthenticated (used by Cloud Run probes).
        service("/health", HealthCheckService.of())

        // Rejected-worker-request count, for the anti-bot-noise metric — see
        // RejectedWorkerRequestCounter. Not behind the prefix: it's operational, not worker-plane.
        service(
            "/internal/metrics",
            HttpService { _, _ ->
                  HttpResponse.of(
                      HttpStatus.OK,
                      MediaType.PLAIN_TEXT_UTF_8,
                      "worker_rejected_requests_total ${RejectedWorkerRequestCounter.get()}\n",
                  )
                }
                .decorate(auth),
        )

        serviceUnder("/", grpcService.decorate(auth).decorate(cors))

        decorator(workerApiPathPrefixDecorator(workerApiPathPrefix, workerPlaneRoutes))
      }
      .build()
}
