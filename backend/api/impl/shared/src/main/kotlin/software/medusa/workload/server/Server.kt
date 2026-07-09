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
private const val workerTokenPath = "/worker/v1/token"

// A fresh, headers-only HttpResponse per call: HttpResponse.of(HttpStatus) synthesizes a text
// body ("404 Not Found"), which fails the "no body" requirement; HttpResponse instances are also
// single-use streams and must not be shared across requests.
private fun bareNotFound(): HttpResponse = HttpResponse.of(ResponseHeaders.of(HttpStatus.NOT_FOUND))

/**
 * Wraps every route so worker-plane requests (path contains `/worker/v1/`) are gated by
 * [workerApiPathPrefix] before they reach any service. A wrong/missing prefix is internet
 * background noise (bot scanners): it gets a bare 404 (no body) counted in
 * [RejectedWorkerRequestCounter], never an audit-log line. Everything else (gRPC, `/health`) passes
 * through untouched — the prefix is anti-noise hygiene, not a security boundary.
 */
private fun workerApiPathPrefixDecorator(
    workerApiPathPrefix: String,
    workerTokenBroker: HttpService?,
): DecoratingHttpServiceFunction = DecoratingHttpServiceFunction { delegate, ctx, req ->
  when (val match = matchWorkerApiPath(ctx.path(), workerApiPathPrefix)) {
    WorkerApiPathMatch.NotWorkerPath -> delegate.serve(ctx, req)
    WorkerApiPathMatch.PrefixMismatch -> {
      RejectedWorkerRequestCounter.increment()
      bareNotFound()
    }
    is WorkerApiPathMatch.Matched -> {
      val isTokenRequest = match.remainder == workerTokenPath && ctx.method() == HttpMethod.POST
      if (workerTokenBroker != null && isTokenRequest) {
        workerTokenBroker.serve(ctx, req)
      } else {
        bareNotFound()
      }
    }
  }
}

fun buildServer(
    originRegex: String,
    port: Int,
    workerApiPathPrefix: String,
    auth: DecoratingHttpServiceFunction,
    counterStore: WorkloadStore,
    workerTokenBroker: HttpService? = null,
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
            enableUnframedRequests(true)
          }
          .build()

  val throttledWorkerTokenBroker =
      workerTokenBroker?.decorate(
          ThrottlingService.newDecorator(ThrottlingStrategy.rateLimiting(workerTokenBrokerQps))
      )

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

        decorator(workerApiPathPrefixDecorator(workerApiPathPrefix, throttledWorkerTokenBroker))
      }
      .build()
}
