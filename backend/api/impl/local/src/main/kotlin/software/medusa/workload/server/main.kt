package software.medusa.workload.server

private const val localPort = 8081
private const val localCorsOriginRegex = """http://localhost(:\d+)?"""

// Fixed, well-known prefix for local dev — there's no bot traffic to shed on localhost, so this
// just needs to match what a locally-running CLI is configured to send.
private const val localWorkerApiPathPrefix = "local-dev"

fun main() {
  val fleetStore = InMemoryFleetStore()

  buildServer(
          originRegex = localCorsOriginRegex,
          port = localPort,
          workerApiPathPrefix = localWorkerApiPathPrefix,
          auth = NoOpAuthDecorator,
          counterStore = InMemoryWorkloadStore(),
          fleetStore = fleetStore,
          impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
          registrationService = RegistrationService(fleetStore),
          selfStatusService = SelfStatusService(fleetStore),
      )
      .start()
      .join()
}
