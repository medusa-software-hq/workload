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
import software.medusa.workload.tokenformat.TokenKind
import software.medusa.workload.tokenformat.WorkloadToken

private const val workerTokenBrokerQps = 5.0

// A fresh, headers-only HttpResponse per call: HttpResponse.of(HttpStatus) synthesizes a text
// body ("404 Not Found"), which fails the "no body" requirement; HttpResponse instances are also
// single-use streams and must not be shared across requests.
internal fun bareNotFound(): HttpResponse =
    HttpResponse.of(ResponseHeaders.of(HttpStatus.NOT_FOUND))

/**
 * Parse-and-drop for `/worker/v2/registrations`: a request whose Bearer credential isn't a
 * well-formed `wle_` enrollment token (or is absent) is bot/scanner noise — dropped with a bare 404
 * *before* the handler or any DB read, counted in [RejectedWorkerRequestCounter], never audited. A
 * well-formed token that then fails to redeem (expired/burnt/unknown) is the handler's business and
 * is audited there.
 */
private val v2EnrollmentTokenDrop: DecoratingHttpServiceFunction =
    DecoratingHttpServiceFunction { delegate, ctx, req ->
      val token = extractBearerToken(req)
      if (token == null || !WorkloadToken.isValid(token, TokenKind.ENROLLMENT)) {
        RejectedWorkerRequestCounter.increment()
        bareNotFound()
      } else {
        delegate.serve(ctx, req)
      }
    }

/**
 * Parse-and-drop + 404-unification for the authenticated v2 endpoints (`/worker/v2/token`,
 * `/claim`, `/registrations/self`). A missing or malformed `<workerId>.<wlw_secret>` bearer — or a
 * secret that isn't a well-formed `wlw_` worker token — is dropped with a bare 404 before the
 * shared resolver runs, counted, never audited (it's noise). A well-formed credential that then
 * fails authentication comes back from the resolver as a 401 (which it audit-logs with the true
 * reason); that 401 is rewritten to the same bare 404 so the whole v2 plane is unprobeable. Post-
 * auth denials (403: no grant, archived, unverified) pass through untouched — the caller has proven
 * membership and deserves a real error.
 */
private val v2WorkerCredentialDrop: DecoratingHttpServiceFunction =
    DecoratingHttpServiceFunction { delegate, ctx, req ->
      val secret = extractBearerToken(req)?.let { parseWorkerBearerToken(it) }?.second
      if (secret == null || !WorkloadToken.isValid(secret, TokenKind.WORKER)) {
        RejectedWorkerRequestCounter.increment()
        bareNotFound()
      } else {
        HttpResponse.of(
            delegate.serve(ctx, req).aggregate().thenApply { aggregated ->
              if (aggregated.status() == HttpStatus.UNAUTHORIZED) bareNotFound()
              else aggregated.toHttpResponse()
            }
        )
      }
    }

fun buildServer(
    originRegex: String,
    port: Int,
    auth: DecoratingHttpServiceFunction,
    fleetStore: FleetStore,
    impersonationVerifier: ImpersonationVerifier,
    imageDigestResolver: ImageDigestResolver,
    workerTokenBroker: HttpService? = null,
    workerIdTokenBroker: HttpService? = null,
    workerClaimService: HttpService? = null,
    selfStatusService: HttpService? = null,
    v2RegistrationService: HttpService? = null,
    workerRunService: HttpService? = null,
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
            addService(FleetServiceImpl(fleetStore, impersonationVerifier, imageDigestResolver))
            enableUnframedRequests(true)
          }
          .build()

  val throttledWorkerTokenBroker =
      workerTokenBroker?.decorate(
          ThrottlingService.newDecorator(ThrottlingStrategy.rateLimiting(workerTokenBrokerQps))
      )
  val throttledWorkerIdTokenBroker =
      workerIdTokenBroker?.decorate(
          ThrottlingService.newDecorator(ThrottlingStrategy.rateLimiting(workerTokenBrokerQps))
      )
  val throttledWorkerClaimService =
      workerClaimService?.decorate(
          ThrottlingService.newDecorator(ThrottlingStrategy.rateLimiting(workerTokenBrokerQps))
      )

  return Server.builder()
      .apply {
        http(port)

        // Health check is unauthenticated (used by Cloud Run probes).
        service("/health", HealthCheckService.of())

        // Rejected-worker-request count, for the anti-bot-noise metric — see
        // RejectedWorkerRequestCounter.
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

        // The worker plane is bare-hostname by design: the wle_/wlw_ token format is the filter, so
        // the endpoints sit directly on the hostname with no path-prefix guard. Only registration
        // has a plane-specific handler (the enrollment-token exchange); token, claim, and
        // self-status authenticate a worker by its stored secret hash. This is what lets the CLI
        // store the broker URL bare and speak the worker API for everything.
        v2RegistrationService?.let {
          route()
              .methods(HttpMethod.POST)
              .path("/worker/v2/registrations")
              .build(it.decorate(v2EnrollmentTokenDrop))
        }
        throttledWorkerTokenBroker?.let {
          route()
              .methods(HttpMethod.POST)
              .path("/worker/v2/token")
              .build(it.decorate(v2WorkerCredentialDrop))
        }
        throttledWorkerIdTokenBroker?.let {
          route()
              .methods(HttpMethod.POST)
              .path("/worker/v2/id-token")
              .build(it.decorate(v2WorkerCredentialDrop))
        }
        throttledWorkerClaimService?.let {
          route()
              .methods(HttpMethod.POST)
              .path("/worker/v2/claim")
              .build(it.decorate(v2WorkerCredentialDrop))
        }
        selfStatusService?.let {
          route()
              .methods(HttpMethod.GET)
              .path("/worker/v2/registrations/self")
              .build(it.decorate(v2WorkerCredentialDrop))
        }
        // The run lifecycle (M6-B1): create, heartbeat, end. Cheap DB writes (unlike the throttled
        // minting endpoints), but worker-authenticated all the same, so they ride the same
        // credential-drop decorator that keeps the plane unprobeable. One service instance backs all
        // three routes; it dispatches on the {runId} path param and the /heartbeat|/end suffix.
        workerRunService?.let {
          route()
              .methods(HttpMethod.POST)
              .path("/worker/v2/runs")
              .build(it.decorate(v2WorkerCredentialDrop))
          route()
              .methods(HttpMethod.POST)
              .path("/worker/v2/runs/{runId}/heartbeat")
              .build(it.decorate(v2WorkerCredentialDrop))
          route()
              .methods(HttpMethod.POST)
              .path("/worker/v2/runs/{runId}/end")
              .build(it.decorate(v2WorkerCredentialDrop))
        }

        serviceUnder("/", grpcService.decorate(auth).decorate(cors))
      }
      .build()
}
