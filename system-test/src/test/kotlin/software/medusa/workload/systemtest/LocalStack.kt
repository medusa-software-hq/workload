package software.medusa.workload.systemtest

import com.linecorp.armeria.server.Server
import software.medusa.workload.server.AlwaysResolvedImageDigestResolver
import software.medusa.workload.server.AlwaysVerifiedImpersonationVerifier
import software.medusa.workload.server.FakeTokenMinter
import software.medusa.workload.server.InMemoryFleetStore
import software.medusa.workload.server.NoOpAuthDecorator
import software.medusa.workload.server.RegistrationServiceV2
import software.medusa.workload.server.SelfStatusService
import software.medusa.workload.server.WorkerClaimService
import software.medusa.workload.server.WorkerIdTokenBrokerService
import software.medusa.workload.server.WorkerRunService
import software.medusa.workload.server.WorkerTokenBrokerService
import software.medusa.workload.server.buildServer

/**
 * The brokered-token lifetime this stack mints with (real backend default: 900s / 15 minutes — see
 * `WorkerTokenBrokerService`/`WorkerClaimService`). Shortened here so the "outlives the token
 * lifetime, refreshes at least once" leg (see [LongRunTokenRefreshSystemTest]) is a matter of
 * seconds in PR CI rather than 15+ minutes. The real 900s constant is what the nightly staging leg
 * exercises for real — this is a scaled-down stand-in for the *mechanism*, not a claim that 8s is
 * the product's token lifetime.
 */
internal const val localTokenLifetimeSeconds = 8L

/**
 * The hermetic local backend a `SYSTEM_TEST_TARGET=local` run drives: the exact same [buildServer]
 * assembly `backend/api/impl/local`'s `main()` boots for local dev — in-memory store, no-op admin
 * auth, fake token minting, always-verified impersonation — reused here rather than reimplemented,
 * so the harness exercises the real server code path. The one deviation is
 * [localTokenLifetimeSeconds] (a constructor parameter on the real
 * [WorkerClaimService]/[WorkerTokenBrokerService], not a fork).
 *
 * One instance per test JVM (started lazily on first use, never explicitly stopped — Armeria's
 * server threads are daemon threads, so they don't keep the JVM alive after the test run ends).
 */
internal object LocalStack {
  private val server: Server by lazy { start() }

  val port: Int
    get() = server.activeLocalPort()

  private fun start(): Server {
    val fleetStore = InMemoryFleetStore()
    val built =
        buildServer(
            originRegex = """http://localhost(:\d+)?""",
            port = 0,
            auth = NoOpAuthDecorator,
            fleetStore = fleetStore,
            impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
            imageDigestResolver = AlwaysResolvedImageDigestResolver,
            workerTokenBroker =
                WorkerTokenBrokerService(
                    fleetStore,
                    FakeTokenMinter,
                    tokenLifetimeSeconds = localTokenLifetimeSeconds,
                ),
            workerIdTokenBroker = WorkerIdTokenBrokerService(fleetStore, FakeTokenMinter),
            workerClaimService =
                WorkerClaimService(
                    fleetStore,
                    FakeTokenMinter,
                    tokenLifetimeSeconds = localTokenLifetimeSeconds,
                ),
            selfStatusService = SelfStatusService(fleetStore),
            v2RegistrationService = RegistrationServiceV2(fleetStore),
            workerRunService = WorkerRunService(fleetStore),
        )
    built.start().join()
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { built.stop().join() } })
    return built
  }
}
