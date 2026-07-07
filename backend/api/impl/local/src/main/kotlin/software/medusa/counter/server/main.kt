package software.medusa.counter.server

private const val localPort = 8081
private const val localCorsOriginRegex = """http://localhost(:\d+)?"""

fun main() {
  buildServer(
          originRegex = localCorsOriginRegex,
          port = localPort,
          auth = NoOpAuthDecorator,
          counterStore = InMemoryCounterStore(),
      )
      .start()
      .join()
}
