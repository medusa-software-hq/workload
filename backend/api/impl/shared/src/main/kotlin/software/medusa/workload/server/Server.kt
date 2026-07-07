package software.medusa.workload.server

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpMethod
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.cors.CorsService
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.server.healthcheck.HealthCheckService

fun buildServer(
    originRegex: String,
    port: Int,
    auth: DecoratingHttpServiceFunction,
    counterStore: WorkloadStore,
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

  return Server.builder()
      .apply {
        http(port)

        // Health check is unauthenticated (used by Cloud Run probes).
        service("/health", HealthCheckService.of())

        serviceUnder("/", grpcService.decorate(auth).decorate(cors))
      }
      .build()
}
