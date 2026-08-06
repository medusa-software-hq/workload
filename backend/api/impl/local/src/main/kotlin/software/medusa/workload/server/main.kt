package software.medusa.workload.server

private const val localPort = 8081
private const val localCorsOriginRegex = """http://localhost(:\d+)?"""

fun main() {
  val fleetStore = InMemoryFleetStore()

  buildServer(
          originRegex = localCorsOriginRegex,
          port = localPort,
          auth = NoOpAuthDecorator,
          fleetStore = fleetStore,
          impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
          imageDigestResolver = AlwaysResolvedImageDigestResolver,
          workerTokenBroker = WorkerTokenBrokerService(fleetStore, FakeTokenMinter),
          workerIdTokenBroker = WorkerIdTokenBrokerService(fleetStore, FakeTokenMinter),
          workerClaimService = WorkerClaimService(fleetStore, FakeTokenMinter),
          selfStatusService = SelfStatusService(fleetStore),
          v2RegistrationService = RegistrationServiceV2(fleetStore),
          workerRunService = WorkerRunService(fleetStore),
          workerAssignmentsService = WorkerAssignmentsService(fleetStore),
      )
      .start()
      .join()
}
