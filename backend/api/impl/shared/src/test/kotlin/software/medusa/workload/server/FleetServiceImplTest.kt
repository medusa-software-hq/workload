package software.medusa.workload.server

import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.server.Server
import io.grpc.Status
import io.grpc.StatusException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.workload.v1.ApproveWorkerRequest
import software.medusa.workload.v1.CreateProfileRequest
import software.medusa.workload.v1.FleetServiceGrpcKt
import software.medusa.workload.v1.GrantProfileRequest
import software.medusa.workload.v1.ListWorkersRequest
import software.medusa.workload.v1.RevokeProfileGrantRequest
import software.medusa.workload.v1.RevokeWorkerRequest
import software.medusa.workload.v1.VerificationStatus
import software.medusa.workload.v1.WorkerStatus

private const val prefix = "test-prefix"

/** Full lifecycle exercised via gRPC, per story 04's acceptance criteria. */
class FleetServiceImplTest {

  private lateinit var fleetStore: FleetStore
  private lateinit var server: Server
  private lateinit var stub: FleetServiceGrpcKt.FleetServiceCoroutineStub

  @BeforeTest
  fun start() {
    fleetStore = InMemoryFleetStore()
    server =
        buildServer(
            originRegex = """http://localhost(:\d+)?""",
            port = 0,
            workerApiPathPrefix = prefix,
            auth = NoOpAuthDecorator,
            counterStore = InMemoryWorkloadStore(),
            fleetStore = fleetStore,
            impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
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
  fun `register (HTTP) to list pending to approve to create profile to grant to revoke`() =
      runBlocking {
        // register (HTTP, story 03) — not gRPC, so drive it directly through the store like the
        // registration endpoint does.
        val secret = generateWorkerSecret()
        val worker =
            fleetStore.createWorker(
                NewWorker(
                    secretHash = hashWorkerSecret(secret),
                    name = "jakub-mbp",
                    hostname = "jakub.local",
                    os = "macos",
                    cliVersion = "1.0.0",
                    confirmationCode = generateConfirmationCode(),
                )
            )

        // list pending — confirmation code visible.
        val pending = stub.listWorkers(ListWorkersRequest.getDefaultInstance()).workersList
        val pendingEntry = pending.single { it.workerId == worker.workerId.value.toString() }
        assertEquals(WorkerStatus.WORKER_STATUS_PENDING, pendingEntry.status)
        assertEquals(worker.confirmationCode, pendingEntry.confirmationCode)

        // approve.
        val approved =
            stub
                .approveWorker(
                    ApproveWorkerRequest.newBuilder().setWorkerId(pendingEntry.workerId).build()
                )
                .worker
        assertEquals(WorkerStatus.WORKER_STATUS_ACTIVE, approved.status)

        // confirmation code no longer surfaced once active.
        val activeEntry =
            stub.listWorkers(ListWorkersRequest.getDefaultInstance()).workersList.single {
              it.workerId == worker.workerId.value.toString()
            }
        assertEquals("", activeEntry.confirmationCode)

        // create profile (implicitly verified).
        val created =
            stub.createProfile(
                CreateProfileRequest.newBuilder()
                    .setProfileId("my-profile-1")
                    .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                    .build()
            )
        assertEquals(1, created.profile.latestRevision)
        assertEquals(
            VerificationStatus.VERIFICATION_STATUS_VERIFIED,
            created.revision.verificationStatus,
        )

        // grant.
        stub.grantProfile(
            GrantProfileRequest.newBuilder()
                .setWorkerId(worker.workerId.value.toString())
                .setProfileId("my-profile-1")
                .build()
        )
        assertTrue(fleetStore.hasGrant(worker.workerId, ProfileId("my-profile-1")))

        // the grant is visible on the worker's proto representation too.
        val withGrant =
            stub.listWorkers(ListWorkersRequest.getDefaultInstance()).workersList.single {
              it.workerId == worker.workerId.value.toString()
            }
        assertEquals(listOf("my-profile-1"), withGrant.grantedProfileIdsList)

        // revoke the grant, then revoke the worker.
        stub.revokeProfileGrant(
            RevokeProfileGrantRequest.newBuilder()
                .setWorkerId(worker.workerId.value.toString())
                .setProfileId("my-profile-1")
                .build()
        )
        assertTrue(!fleetStore.hasGrant(worker.workerId, ProfileId("my-profile-1")))

        val revoked =
            stub
                .revokeWorker(
                    RevokeWorkerRequest.newBuilder()
                        .setWorkerId(worker.workerId.value.toString())
                        .build()
                )
                .worker
        assertEquals(WorkerStatus.WORKER_STATUS_REVOKED, revoked.status)
      }

  @Test
  fun `approving an already-active worker fails with FAILED_PRECONDITION, not a crash`() =
      runBlocking {
        val worker =
            fleetStore.createWorker(
                NewWorker(
                    secretHash = hashWorkerSecret(generateWorkerSecret()),
                    name = "worker-1",
                    hostname = null,
                    os = null,
                    cliVersion = null,
                    confirmationCode = generateConfirmationCode(),
                )
            )
        fleetStore.approveWorker(worker.workerId, approvedBy = "admin@example.com")

        val exception =
            assertFailsWith<StatusException> {
              stub.approveWorker(
                  ApproveWorkerRequest.newBuilder()
                      .setWorkerId(worker.workerId.value.toString())
                      .build()
              )
            }
        assertEquals(Status.Code.FAILED_PRECONDITION, exception.status.code)
      }

  @Test
  fun `revoking a pending (not yet active) worker fails with FAILED_PRECONDITION`() = runBlocking {
    val worker =
        fleetStore.createWorker(
            NewWorker(
                secretHash = hashWorkerSecret(generateWorkerSecret()),
                name = "worker-1",
                hostname = null,
                os = null,
                cliVersion = null,
                confirmationCode = generateConfirmationCode(),
            )
        )

    val exception =
        assertFailsWith<StatusException> {
          stub.revokeWorker(
              RevokeWorkerRequest.newBuilder().setWorkerId(worker.workerId.value.toString()).build()
          )
        }
    assertEquals(Status.Code.FAILED_PRECONDITION, exception.status.code)
  }

  @Test
  fun `updateProfile bumps the revision and preserves the prior one`() = runBlocking {
    stub.createProfile(
        CreateProfileRequest.newBuilder()
            .setProfileId("my-profile-2")
            .setTargetServiceAccount("sa-v1@project.iam.gserviceaccount.com")
            .build()
    )

    val updated =
        stub.updateProfile(
            software.medusa.workload.v1.UpdateProfileRequest.newBuilder()
                .setProfileId("my-profile-2")
                .setTargetServiceAccount("sa-v2@project.iam.gserviceaccount.com")
                .build()
        )
    assertEquals(2, updated.profile.latestRevision)
    assertEquals(2, updated.revision.revision)

    val revisions =
        stub
            .listProfileRevisions(
                software.medusa.workload.v1.ListProfileRevisionsRequest.newBuilder()
                    .setProfileId("my-profile-2")
                    .build()
            )
            .revisionsList
    assertEquals(listOf(1, 2), revisions.map { it.revision })
    assertEquals("sa-v1@project.iam.gserviceaccount.com", revisions[0].targetServiceAccount)
    assertEquals("sa-v2@project.iam.gserviceaccount.com", revisions[1].targetServiceAccount)
  }

  @Test
  fun `createProfile round-trips env_vars and secret_env_vars`() = runBlocking {
    val created =
        stub.createProfile(
            CreateProfileRequest.newBuilder()
                .setProfileId("my-profile-env")
                .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                .putEnvVars("MODE", "batch")
                .putSecretEnvVars("API_KEY", "projects/p/secrets/api-key/versions/latest")
                .build()
        )

    assertEquals(mapOf("MODE" to "batch"), created.revision.envVarsMap)
    assertEquals(
        mapOf("API_KEY" to "projects/p/secrets/api-key/versions/latest"),
        created.revision.secretEnvVarsMap,
    )
  }

  @Test
  fun `createProfile rejects an invalid env var name`() = runBlocking {
    val exception =
        assertFailsWith<StatusException> {
          stub.createProfile(
              CreateProfileRequest.newBuilder()
                  .setProfileId("my-profile-bad-env")
                  .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                  .putEnvVars("not-a-valid-name", "value")
                  .build()
          )
        }
    assertEquals(Status.Code.INVALID_ARGUMENT, exception.status.code)
  }

  @Test
  fun `createProfile rejects a name set in both env_vars and secret_env_vars`() = runBlocking {
    val exception =
        assertFailsWith<StatusException> {
          stub.createProfile(
              CreateProfileRequest.newBuilder()
                  .setProfileId("my-profile-collision")
                  .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                  .putEnvVars("API_KEY", "plain-value")
                  .putSecretEnvVars("API_KEY", "projects/p/secrets/api-key/versions/latest")
                  .build()
          )
        }
    assertEquals(Status.Code.INVALID_ARGUMENT, exception.status.code)
  }

  @Test
  fun `createProfile rejects a malformed secret resource name`() = runBlocking {
    val exception =
        assertFailsWith<StatusException> {
          stub.createProfile(
              CreateProfileRequest.newBuilder()
                  .setProfileId("my-profile-bad-secret")
                  .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                  .putSecretEnvVars("API_KEY", "not-a-secret-manager-resource-name")
                  .build()
          )
        }
    assertEquals(Status.Code.INVALID_ARGUMENT, exception.status.code)
  }

  @Test
  fun `a revision whose secrets the target SA can't read is flagged secret_inaccessible`() =
      runBlocking {
        val secretAwareServer =
            buildServer(
                originRegex = """http://localhost(:\d+)?""",
                port = 0,
                workerApiPathPrefix = prefix,
                auth = NoOpAuthDecorator,
                counterStore = InMemoryWorkloadStore(),
                fleetStore = InMemoryFleetStore(),
                impersonationVerifier = SecretInaccessibleVerifier,
            )
        secretAwareServer.start().join()
        try {
          val secretAwareStub =
              GrpcClients.newClient(
                  "gproto+http://127.0.0.1:${secretAwareServer.activeLocalPort()}/",
                  FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
              )

          val created =
              secretAwareStub.createProfile(
                  CreateProfileRequest.newBuilder()
                      .setProfileId("my-profile-secret-check")
                      .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                      .putSecretEnvVars("API_KEY", "projects/p/secrets/api-key/versions/latest")
                      .build()
              )

          assertEquals(
              VerificationStatus.VERIFICATION_STATUS_SECRET_INACCESSIBLE,
              created.revision.verificationStatus,
          )
        } finally {
          secretAwareServer.stop().join()
        }
      }
}

/** Reports success unless the revision references any secrets, which it always flags. */
private object SecretInaccessibleVerifier : ImpersonationVerifier {
  override suspend fun verify(
      targetServiceAccount: String,
      secretEnvVars: Map<String, String>,
  ): software.medusa.workload.server.VerificationStatus =
      if (secretEnvVars.isEmpty()) {
        software.medusa.workload.server.VerificationStatus.VERIFIED
      } else {
        software.medusa.workload.server.VerificationStatus.SECRET_INACCESSIBLE
      }
}
