package software.medusa.workload.server

/**
 * HTTP header names relevant to gRPC-Web and Connect protocol CORS configuration.
 *
 * gRPC core headers: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-HTTP2.md
 *
 * gRPC-Web spec: https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md
 *
 * Connect protocol spec: https://connectrpc.com/docs/protocol
 */
data object GrpcHeaderNames {
  // -- Standard gRPC headers (gRPC HTTP/2 protocol) --
  const val GRPC_STATUS = "grpc-status"
  const val GRPC_MESSAGE = "grpc-message"
  const val GRPC_TIMEOUT = "grpc-timeout"

  // -- gRPC-Web headers --

  /** gRPC-Web framing indicator sent by browsers. */
  const val X_GRPC_WEB = "x-grpc-web"

  /**
   * Sent by gRPC-Web clients to identify the client implementation. Distinct from the standard HTTP
   * `User-Agent` which browsers forbid scripts from setting.
   */
  const val X_USER_AGENT = "x-user-agent"

  // -- Connect protocol headers --

  /** Connect protocol version negotiation header. */
  const val CONNECT_PROTOCOL_VERSION = "connect-protocol-version"

  /** Connect protocol request timeout header. */
  const val CONNECT_TIMEOUT_MS = "connect-timeout-ms"
}
