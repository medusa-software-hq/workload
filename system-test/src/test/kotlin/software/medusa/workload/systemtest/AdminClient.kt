package software.medusa.workload.systemtest

import com.linecorp.armeria.client.grpc.GrpcClients
import io.grpc.Metadata
import io.grpc.stub.MetadataUtils
import software.medusa.workload.v1.ArchiveProfileRequest
import software.medusa.workload.v1.CreateEnrollmentTokenRequest
import software.medusa.workload.v1.CreateProfileRequest
import software.medusa.workload.v1.EnrollmentToken
import software.medusa.workload.v1.FleetServiceGrpcKt
import software.medusa.workload.v1.GrantProfileRequest
import software.medusa.workload.v1.ListEnrollmentTokensRequest
import software.medusa.workload.v1.ListRunsRequest
import software.medusa.workload.v1.ListWorkersRequest
import software.medusa.workload.v1.Profile
import software.medusa.workload.v1.ProfileRevision
import software.medusa.workload.v1.RevokeEnrollmentTokenRequest
import software.medusa.workload.v1.RevokeProfileGrantRequest
import software.medusa.workload.v1.RevokeWorkerRequest
import software.medusa.workload.v1.Run
import software.medusa.workload.v1.SetProfileFallbackEligibleRequest
import software.medusa.workload.v1.SetWorkerFallbackNodeRequest
import software.medusa.workload.v1.Worker

/**
 * A thin, test-scoped wrapper over the generated [FleetServiceGrpcKt.FleetServiceCoroutineStub] —
 * the same admin gRPC contract the console speaks. Everything the harness needs to set up fixtures
 * (enrollment tokens, workers, profiles, grants) and to assert outcomes (worker/profile/run state)
 * goes through here rather than through the CLI, so assertions observe the backend's own truth
 * independently of how the CLI chooses to report it.
 */
internal class AdminClient(private val stub: FleetServiceGrpcKt.FleetServiceCoroutineStub) {

  suspend fun createEnrollmentToken(
      note: String,
      requireApproval: Boolean = false,
      expiresInDays: Int = 1,
  ): Pair<String, EnrollmentToken> {
    val response =
        stub.createEnrollmentToken(
            CreateEnrollmentTokenRequest.newBuilder()
                .setNote(note)
                .setExpiresInDays(expiresInDays)
                .setRequireApproval(requireApproval)
                .build()
        )
    return response.token to response.enrollmentToken
  }

  suspend fun listEnrollmentTokens(): List<EnrollmentToken> =
      stub
          .listEnrollmentTokens(ListEnrollmentTokensRequest.getDefaultInstance())
          .enrollmentTokensList

  suspend fun revokeEnrollmentToken(id: String) {
    stub.revokeEnrollmentToken(
        RevokeEnrollmentTokenRequest.newBuilder().setEnrollmentTokenId(id).build()
    )
  }

  suspend fun listWorkers(): List<Worker> =
      stub.listWorkers(ListWorkersRequest.getDefaultInstance()).workersList

  suspend fun listProfiles(): List<Profile> =
      stub
          .listProfiles(software.medusa.workload.v1.ListProfilesRequest.getDefaultInstance())
          .profilesList

  suspend fun findWorkerByName(name: String): Worker? = listWorkers().find { it.name == name }

  suspend fun approveWorker(workerId: String): Worker =
      stub
          .approveWorker(
              software.medusa.workload.v1.ApproveWorkerRequest.newBuilder()
                  .setWorkerId(workerId)
                  .build()
          )
          .worker

  suspend fun rejectWorker(workerId: String): Worker =
      stub
          .rejectWorker(
              software.medusa.workload.v1.RejectWorkerRequest.newBuilder()
                  .setWorkerId(workerId)
                  .build()
          )
          .worker

  suspend fun revokeWorker(workerId: String): Worker =
      stub.revokeWorker(RevokeWorkerRequest.newBuilder().setWorkerId(workerId).build()).worker

  /**
   * Flags/unflags [workerId] as (one of) the shared fallback node(s) — see
   * [software.medusa.workload.server.FallbackPlacementReconciler]. Triggers a fleet-wide reconcile
   * server-side, so fallback-eligible profiles land on (or move off) this worker as a side effect
   * of this call.
   */
  suspend fun setWorkerFallbackNode(workerId: String, fallbackNode: Boolean): Worker =
      stub
          .setWorkerFallbackNode(
              SetWorkerFallbackNodeRequest.newBuilder()
                  .setWorkerId(workerId)
                  .setFallbackNode(fallbackNode)
                  .build()
          )
          .worker

  /**
   * Tags/untags [profileId] for fallback auto-placement; triggers a reconcile of just this profile.
   */
  suspend fun setProfileFallbackEligible(profileId: String, fallbackEligible: Boolean): Profile =
      stub
          .setProfileFallbackEligible(
              SetProfileFallbackEligibleRequest.newBuilder()
                  .setProfileId(profileId)
                  .setFallbackEligible(fallbackEligible)
                  .build()
          )
          .profile

  suspend fun createExecProfile(
      profileId: String,
      targetServiceAccount: String,
      envVars: Map<String, String> = emptyMap(),
  ): Pair<Profile, ProfileRevision> {
    val response =
        stub.createProfile(
            CreateProfileRequest.newBuilder()
                .setProfileId(profileId)
                .setDisplayName(profileId)
                .setTargetServiceAccount(targetServiceAccount)
                .putAllEnvVars(envVars)
                .build()
        )
    return response.profile to response.revision
  }

  suspend fun createImageProfile(
      profileId: String,
      targetServiceAccount: String,
      dockerImage: String,
  ): Pair<Profile, ProfileRevision> {
    val response =
        stub.createProfile(
            CreateProfileRequest.newBuilder()
                .setProfileId(profileId)
                .setDisplayName(profileId)
                .setTargetServiceAccount(targetServiceAccount)
                .setDockerImage(dockerImage)
                .build()
        )
    return response.profile to response.revision
  }

  suspend fun archiveProfile(profileId: String): Profile =
      stub
          .archiveProfile(ArchiveProfileRequest.newBuilder().setProfileId(profileId).build())
          .profile

  suspend fun grantProfile(workerId: String, profileId: String) {
    stub.grantProfile(
        GrantProfileRequest.newBuilder().setWorkerId(workerId).setProfileId(profileId).build()
    )
  }

  suspend fun revokeProfileGrant(workerId: String, profileId: String) {
    stub.revokeProfileGrant(
        RevokeProfileGrantRequest.newBuilder().setWorkerId(workerId).setProfileId(profileId).build()
    )
  }

  suspend fun listRuns(
      profileId: String = "",
      workerId: String = "",
      liveOnly: Boolean = false,
  ): List<Run> =
      stub
          .listRuns(
              ListRunsRequest.newBuilder()
                  .setProfileId(profileId)
                  .setWorkerId(workerId)
                  .setLiveOnly(liveOnly)
                  .build()
          )
          .runsList

  companion object {
    /**
     * Against the hermetic local stack: [software.medusa.workload.server.NoOpAuthDecorator] needs
     * no credential.
     */
    fun local(port: Int): AdminClient =
        AdminClient(
            GrpcClients.newClient(
                "gproto+http://127.0.0.1:$port/",
                FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
            )
        )

    /** Against a deployed environment: a bearer ID token for an allowlisted s2s admin principal. */
    fun staging(apiBaseUrl: String, idToken: String): AdminClient {
      val stub =
          GrpcClients.newClient(
              "gproto+$apiBaseUrl/",
              FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
          )
      val headers = Metadata().apply { put(AUTHORIZATION_KEY, "Bearer $idToken") }
      return AdminClient(stub.withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers)))
    }

    private val AUTHORIZATION_KEY: Metadata.Key<String> =
        Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
  }
}
