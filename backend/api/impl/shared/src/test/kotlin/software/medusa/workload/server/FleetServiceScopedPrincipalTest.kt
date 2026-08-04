package software.medusa.workload.server

import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServiceRequestContext
import io.grpc.StatusException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import software.medusa.workload.v1.CreateProfileRequest
import software.medusa.workload.v1.FleetServiceGrpcKt
import software.medusa.workload.v1.UpdateProfileRequest

/**
 * A fixed [Principal.Service] auth decorator standing in for a real service-account token, so this
 * exercises exactly what [GooglePrincipalVerifier] would hand [FleetServiceImpl] for a CI principal
 * (workload#126 Phase 2), without minting real Google tokens.
 */
private class FixedPrincipalAuthDecorator(private val principal: Principal) :
    DecoratingHttpServiceFunction {
  override fun serve(delegate: HttpService, ctx: ServiceRequestContext, req: HttpRequest): HttpResponse {
    ctx.setAttr(adminPrincipalAttrKey, principal)
    return delegate.serve(ctx, req)
  }
}

/**
 * Exercises the profile-scoped [Principal.Service] authorization added for the automated-rollout
 * epic's CI-push phase: a CI principal restricted to `flow-worker` can UpdateProfile that one
 * profile, but nothing else — not a differently-named profile, and no other mutating RPC.
 */
class FleetServiceScopedPrincipalTest {

  private lateinit var fleetStore: FleetStore
  private lateinit var humanServer: Server
  private lateinit var humanStub: FleetServiceGrpcKt.FleetServiceCoroutineStub
  private lateinit var scopedServer: Server
  private lateinit var scopedStub: FleetServiceGrpcKt.FleetServiceCoroutineStub

  private val scopedPrincipal = Principal.Service("flow-ci@example.iam.gserviceaccount.com", allowedProfileIds = setOf("flow-worker"))

  @BeforeTest
  fun start() {
    fleetStore = InMemoryFleetStore()

    humanServer =
        buildServer(
            originRegex = """http://localhost(:\d+)?""",
            port = 0,
            auth = NoOpAuthDecorator,
            fleetStore = fleetStore,
            impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
            imageDigestResolver = AlwaysResolvedImageDigestResolver,
        )
    humanServer.start().join()
    humanStub =
        GrpcClients.newClient(
            "gproto+http://127.0.0.1:${humanServer.activeLocalPort()}/",
            FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
        )

    scopedServer =
        buildServer(
            originRegex = """http://localhost(:\d+)?""",
            port = 0,
            auth = FixedPrincipalAuthDecorator(scopedPrincipal),
            fleetStore = fleetStore,
            impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
            imageDigestResolver = AlwaysResolvedImageDigestResolver,
        )
    scopedServer.start().join()
    scopedStub =
        GrpcClients.newClient(
            "gproto+http://127.0.0.1:${scopedServer.activeLocalPort()}/",
            FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
        )
  }

  @AfterTest
  fun stop() {
    humanServer.stop().join()
    scopedServer.stop().join()
  }

  private fun createProfile(profileId: String) = runBlocking {
    humanStub.createProfile(
        CreateProfileRequest.newBuilder()
            .setProfileId(profileId)
            .setTargetServiceAccount("target@example.iam.gserviceaccount.com")
            .build()
    )
  }

  @Test
  fun `a scoped CI principal can update the profile it is scoped to`() = runBlocking {
    createProfile("flow-worker")

    val response =
        scopedStub.updateProfile(
            UpdateProfileRequest.newBuilder()
                .setProfileId("flow-worker")
                .setTargetServiceAccount("target@example.iam.gserviceaccount.com")
                .setDockerImage("us-docker.pkg.dev/proj/repo/flow-worker:latest")
                .build()
        )

    assertEquals(2, response.revision.revision)
  }

  @Test
  fun `a scoped CI principal cannot update a profile outside its scope`() = runBlocking {
    createProfile("other-profile")

    val error =
        assertFailsWith<StatusException> {
          scopedStub.updateProfile(
              UpdateProfileRequest.newBuilder()
                  .setProfileId("other-profile")
                  .setTargetServiceAccount("target@example.iam.gserviceaccount.com")
                  .build()
          )
        }
    assertEquals(io.grpc.Status.Code.PERMISSION_DENIED, error.status.code)
  }

  @Test
  fun `a scoped CI principal cannot create a new profile`() = runBlocking {
    val error =
        assertFailsWith<StatusException> {
          scopedStub.createProfile(
              CreateProfileRequest.newBuilder()
                  .setProfileId("flow-worker")
                  .setTargetServiceAccount("target@example.iam.gserviceaccount.com")
                  .build()
          )
        }
    assertEquals(io.grpc.Status.Code.PERMISSION_DENIED, error.status.code)
  }
}
