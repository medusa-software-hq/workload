package software.medusa.workload.server

private const val localPort = 8081
private const val localCorsOriginRegex = """http://localhost(:\d+)?"""

fun main() {
  buildServer(
          originRegex = localCorsOriginRegex,
          port = localPort,
          auth = NoOpAuthDecorator,
          counterStore = InMemoryWorkloadStore(),
      )
      .start()
      .join()
}
