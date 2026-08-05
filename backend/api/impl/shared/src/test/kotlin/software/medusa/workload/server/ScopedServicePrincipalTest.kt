package software.medusa.workload.server

import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.DecoratingHttpServiceFunction
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.Server
import com.linecorp.armeria.server.ServiceRequestContext
import io.grpc.Status
import io.grpc.StatusException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.runBlocking
import software.medusa.workload.v1.CreateProfileRequest
import software.medusa.workload.v1.FleetServiceGrpcKt
import software.medusa.workload.v1.ListProfilesRequest
import software.medusa.workload.v1.ListRunsRequest
import software.medusa.workload.v1.ListWorkersRequest
import software.medusa.workload.v1.UpdateProfileRequest

private const val scopedServiceEmail = "flow-ci@ms-workload.iam.gserviceaccount.com"

/** Always classifies the caller as a [Principal.Service] scoped to a fixed set of profile ids. */
private class FixedScopedServiceDecorator(private val profileScope: Set<String>) :
    DecoratingHttpServiceFunction {
  override fun serve(
      delegate: HttpService,
      ctx: ServiceRequestContext,
      req: HttpRequest,
  ): HttpResponse {
    ctx.setAttr(adminPrincipalAttrKey, Principal.Service(scopedServiceEmail, profileScope))
    return delegate.serve(ctx, req)
  }
}

/**
 * Phase 2 of workload#126: a CI principal scoped to specific profile ids (e.g. Flow's
 * digest-pushing automation) can create/update exactly those profiles and nothing else — every
 * other admin RPC, and every out-of-scope profile id, is rejected with PERMISSION_DENIED.
 */
class ScopedServicePrincipalTest {

  private lateinit var fleetStore: FleetStore
  private lateinit var server: Server
  private lateinit var stub: FleetServiceGrpcKt.FleetServiceCoroutineStub

  private fun start(profileScope: Set<String>) {
    fleetStore = InMemoryFleetStore()
    server =
        buildServer(
            originRegex = """http://localhost(:\d+)?""",
            port = 0,
            auth = FixedScopedServiceDecorator(profileScope),
            fleetStore = fleetStore,
            impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
            imageDigestResolver = AlwaysResolvedImageDigestResolver,
        )
    server.start().join()
    stub =
        GrpcClients.newClient(
            "gproto+http://127.0.0.1:${server.activeLocalPort()}/",
            FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
        )
  }

  @AfterTest
  fun stop() {
    server.stop().join()
  }

  @Test
  fun `a scoped principal can create a profile inside its scope`() = runBlocking {
    start(setOf("flow-worker"))
    val response =
        stub.createProfile(
            CreateProfileRequest.newBuilder()
                .setProfileId("flow-worker")
                .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                .build()
        )
    assertEquals("flow-worker", response.profile.profileId)
  }

  @Test
  fun `a scoped principal cannot create a profile outside its scope`() = runBlocking {
    start(setOf("flow-worker"))
    val error =
        assertFailsWith<StatusException> {
          stub.createProfile(
              CreateProfileRequest.newBuilder()
                  .setProfileId("some-other-profile")
                  .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                  .build()
          )
        }
    assertEquals(Status.Code.PERMISSION_DENIED, error.status.code)
  }

  @Test
  fun `a scoped principal can update a profile inside its scope`() = runBlocking {
    start(setOf("flow-worker"))
    stub.createProfile(
        CreateProfileRequest.newBuilder()
            .setProfileId("flow-worker")
            .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
            .build()
    )
    val response =
        stub.updateProfile(
            UpdateProfileRequest.newBuilder()
                .setProfileId("flow-worker")
                .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                .setDrainDeadline("6h")
                .build()
        )
    assertEquals("6h", response.revision.drainDeadline)
  }

  @Test
  fun `a scoped principal is denied every non-profile-update RPC`() = runBlocking {
    start(setOf("flow-worker"))
    assertFailsWith<StatusException> { stub.listWorkers(ListWorkersRequest.getDefaultInstance()) }
        .let { assertEquals(Status.Code.PERMISSION_DENIED, it.status.code) }
    assertFailsWith<StatusException> { stub.listProfiles(ListProfilesRequest.getDefaultInstance()) }
        .let { assertEquals(Status.Code.PERMISSION_DENIED, it.status.code) }
  }

  // Block-bodied (not `= runBlocking { ... }`): a JUnit5 @Test method must return void, and an
  // expression body's inferred return type follows the block's last statement, which here is a
  // non-Unit RPC response — that silently drops the test from discovery (no compile error, no test
  // failure, it just never runs). See the two fixes below.
  @Test
  fun `a scoped principal must filter ListRuns by an in-scope profile id`() {
    runBlocking {
      start(setOf("flow-worker"))
      assertFailsWith<StatusException> { stub.listRuns(ListRunsRequest.getDefaultInstance()) }
          .let { assertEquals(Status.Code.PERMISSION_DENIED, it.status.code) }
      assertFailsWith<StatusException> {
            stub.listRuns(ListRunsRequest.newBuilder().setProfileId("some-other-profile").build())
          }
          .let { assertEquals(Status.Code.PERMISSION_DENIED, it.status.code) }
      // In-scope filter is fine (empty result, no profile created yet — just proves it isn't
      // denied).
      stub.listRuns(ListRunsRequest.newBuilder().setProfileId("flow-worker").build())
    }
  }

  @Test
  fun `an unrestricted service principal (empty scope) keeps full access`() {
    runBlocking {
      start(emptySet())
      stub.listWorkers(ListWorkersRequest.getDefaultInstance())
      stub.listProfiles(ListProfilesRequest.getDefaultInstance())
      stub.createProfile(
          CreateProfileRequest.newBuilder()
              .setProfileId("any-profile")
              .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
              .build()
      )
    }
  }
}
