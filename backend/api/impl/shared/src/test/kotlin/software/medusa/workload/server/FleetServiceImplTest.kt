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
import software.medusa.workload.tokenformat.TokenKind
import software.medusa.workload.tokenformat.WorkloadToken
import software.medusa.workload.v1.ApproveWorkerRequest
import software.medusa.workload.v1.CreateAssignmentRequest
import software.medusa.workload.v1.CreateEnrollmentTokenRequest
import software.medusa.workload.v1.CreateProfileRequest
import software.medusa.workload.v1.DeleteAssignmentRequest
import software.medusa.workload.v1.FleetServiceGrpcKt
import software.medusa.workload.v1.GrantProfileRequest
import software.medusa.workload.v1.ListAssignmentsRequest
import software.medusa.workload.v1.ListEnrollmentTokensRequest
import software.medusa.workload.v1.ListProfilesRequest
import software.medusa.workload.v1.ListRunsRequest
import software.medusa.workload.v1.ListWorkersRequest
import software.medusa.workload.v1.RevokeEnrollmentTokenRequest
import software.medusa.workload.v1.RevokeProfileGrantRequest
import software.medusa.workload.v1.RevokeWorkerRequest
import software.medusa.workload.v1.RunState as RunStateProto
import software.medusa.workload.v1.VerificationStatus
import software.medusa.workload.v1.VerifyProfileRequest
import software.medusa.workload.v1.WorkerStatus

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
            auth = NoOpAuthDecorator,
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
  fun `register (HTTP) to list pending to approve to create profile to grant to revoke`() =
      runBlocking {
        // register (HTTP, story 03) — not gRPC, so drive it directly through the store like the
        // registration endpoint does.
        val secret = WorkloadToken.generate(TokenKind.WORKER)
        val worker =
            fleetStore.createWorker(
                NewWorker(
                    secretHash = hashWorkerSecret(secret),
                    name = "jakub-mbp",
                    hostname = "jakub.local",
                    os = "macos",
                    cliVersion = "1.0.0",
                )
            )

        // list pending.
        val pending = stub.listWorkers(ListWorkersRequest.getDefaultInstance()).workersList
        val pendingEntry = pending.single { it.workerId == worker.workerId.value.toString() }
        assertEquals(WorkerStatus.WORKER_STATUS_PENDING, pendingEntry.status)

        // approve.
        val approved =
            stub
                .approveWorker(
                    ApproveWorkerRequest.newBuilder().setWorkerId(pendingEntry.workerId).build()
                )
                .worker
        assertEquals(WorkerStatus.WORKER_STATUS_ACTIVE, approved.status)

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
  fun `create, list, and delete an assignment — a first-class record distinct from a grant`() =
      runBlocking {
        val worker =
            fleetStore.createWorker(
                NewWorker(
                    secretHash = hashWorkerSecret(WorkloadToken.generate(TokenKind.WORKER)),
                    name = "worker-assign",
                    hostname = null,
                    os = null,
                    cliVersion = null,
                )
            )
        stub.createProfile(
            CreateProfileRequest.newBuilder()
                .setProfileId("assignment-profile")
                .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                .build()
        )

        val created =
            stub
                .createAssignment(
                    CreateAssignmentRequest.newBuilder()
                        .setWorkerId(worker.workerId.value.toString())
                        .setProfileId("assignment-profile")
                        .build()
                )
                .assignment
        assertEquals(worker.workerId.value.toString(), created.workerId)
        assertEquals("assignment-profile", created.profileId)
        assertTrue(created.assignmentId.isNotBlank())
        // Creating an assignment must not also create a grant.
        assertTrue(!fleetStore.hasGrant(worker.workerId, ProfileId("assignment-profile")))

        val listed =
            stub.listAssignments(ListAssignmentsRequest.getDefaultInstance()).assignmentsList
        assertTrue(listed.any { it.assignmentId == created.assignmentId })

        stub.deleteAssignment(
            DeleteAssignmentRequest.newBuilder().setAssignmentId(created.assignmentId).build()
        )
        val afterDelete =
            stub.listAssignments(ListAssignmentsRequest.getDefaultInstance()).assignmentsList
        assertTrue(afterDelete.none { it.assignmentId == created.assignmentId })

        assertFailsWith<StatusException> {
          stub.deleteAssignment(
              DeleteAssignmentRequest.newBuilder().setAssignmentId(created.assignmentId).build()
          )
        }
      }

  @Test
  fun `approving an already-active worker fails with FAILED_PRECONDITION, not a crash`() =
      runBlocking {
        val worker =
            fleetStore.createWorker(
                NewWorker(
                    secretHash = hashWorkerSecret(WorkloadToken.generate(TokenKind.WORKER)),
                    name = "worker-1",
                    hostname = null,
                    os = null,
                    cliVersion = null,
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
                secretHash = hashWorkerSecret(WorkloadToken.generate(TokenKind.WORKER)),
                name = "worker-1",
                hostname = null,
                os = null,
                cliVersion = null,
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
                auth = NoOpAuthDecorator,
                fleetStore = InMemoryFleetStore(),
                impersonationVerifier = SecretInaccessibleVerifier,
                imageDigestResolver = AlwaysResolvedImageDigestResolver,
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

  @Test
  fun `createProfile rejects a malformed docker image ref`() = runBlocking {
    val exception =
        assertFailsWith<StatusException> {
          stub.createProfile(
              CreateProfileRequest.newBuilder()
                  .setProfileId("my-profile-bad-image")
                  .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                  // Bare Docker Hub shorthand — not a fully-qualified registry ref.
                  .setDockerImage("busybox:latest")
                  .build()
          )
        }
    assertEquals(Status.Code.INVALID_ARGUMENT, exception.status.code)
  }

  @Test
  fun `an image revision resolves a digest at creation and a moved tag yields a new digest`() =
      runBlocking {
        val resolver = ScriptedImageDigestResolver()
        val imageServer = imageResolvingServer(resolver)
        imageServer.start().join()
        try {
          val imageStub =
              GrpcClients.newClient(
                  "gproto+http://127.0.0.1:${imageServer.activeLocalPort()}/",
                  FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
              )

          resolver.next = ImageResolution(ImageStatus.RESOLVED, digest = "sha256:aaa")
          val created =
              imageStub.createProfile(
                  CreateProfileRequest.newBuilder()
                      .setProfileId("my-profile-image-svc")
                      .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                      .setDockerImage("us-docker.pkg.dev/p/repo/app:v1")
                      .build()
              )
          assertEquals("us-docker.pkg.dev/p/repo/app:v1", created.revision.dockerImage)
          assertEquals("sha256:aaa", created.revision.dockerImageDigest)
          assertEquals(
              software.medusa.workload.v1.ImageStatus.IMAGE_STATUS_RESOLVED,
              created.revision.imageStatus,
          )

          // Re-pushing the same tag → the resolver now returns a different digest; the new revision
          // records it (the console diff surfaces this as a digest change under an identical tag).
          resolver.next = ImageResolution(ImageStatus.RESOLVED, digest = "sha256:bbb")
          val updated =
              imageStub.updateProfile(
                  software.medusa.workload.v1.UpdateProfileRequest.newBuilder()
                      .setProfileId("my-profile-image-svc")
                      .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                      .setDockerImage("us-docker.pkg.dev/p/repo/app:v1")
                      .build()
              )
          assertEquals("us-docker.pkg.dev/p/repo/app:v1", updated.revision.dockerImage)
          assertEquals("sha256:bbb", updated.revision.dockerImageDigest)
        } finally {
          imageServer.stop().join()
        }
      }

  @Test
  fun `an unresolvable image flags the revision UNRESOLVABLE`() = runBlocking {
    val resolver = ScriptedImageDigestResolver()
    val imageServer = imageResolvingServer(resolver)
    imageServer.start().join()
    try {
      val imageStub =
          GrpcClients.newClient(
              "gproto+http://127.0.0.1:${imageServer.activeLocalPort()}/",
              FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
          )
      resolver.next = ImageResolution(ImageStatus.UNRESOLVABLE, detail = "fake: registry 404")
      val created =
          imageStub.createProfile(
              CreateProfileRequest.newBuilder()
                  .setProfileId("my-profile-image-bad")
                  .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                  .setDockerImage("us-docker.pkg.dev/p/repo/missing:v9")
                  .build()
          )
      assertEquals(
          software.medusa.workload.v1.ImageStatus.IMAGE_STATUS_UNRESOLVABLE,
          created.revision.imageStatus,
      )
      assertEquals("", created.revision.dockerImageDigest)
    } finally {
      imageServer.stop().join()
    }
  }

  @Test
  fun `ResolveImage previews the digest without creating a profile`() = runBlocking {
    val resolver = ScriptedImageDigestResolver()
    val server = imageResolvingServer(resolver)
    server.start().join()
    try {
      val stub =
          GrpcClients.newClient(
              "gproto+http://127.0.0.1:${server.activeLocalPort()}/",
              FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
          )
      resolver.next = ImageResolution(ImageStatus.RESOLVED, digest = "sha256:previewed")

      val preview =
          stub.resolveImage(
              software.medusa.workload.v1.ResolveImageRequest.newBuilder()
                  .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                  .setDockerImage("us-docker.pkg.dev/p/repo/app:v1")
                  .build()
          )
      assertEquals("sha256:previewed", preview.dockerImageDigest)
      assertEquals(
          software.medusa.workload.v1.ImageStatus.IMAGE_STATUS_RESOLVED,
          preview.imageStatus,
      )
      // Read-only: no profile was created.
      assertTrue(stub.listProfiles(ListProfilesRequest.getDefaultInstance()).profilesList.isEmpty())
    } finally {
      server.stop().join()
    }
  }

  @Test
  fun `ResolveImage rejects a non-Google registry, minting nothing`() = runBlocking {
    val server = imageResolvingServer(ScriptedImageDigestResolver())
    server.start().join()
    try {
      val stub =
          GrpcClients.newClient(
              "gproto+http://127.0.0.1:${server.activeLocalPort()}/",
              FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
          )
      val e =
          assertFailsWith<StatusException> {
            stub.resolveImage(
                software.medusa.workload.v1.ResolveImageRequest.newBuilder()
                    .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                    .setDockerImage("ghcr.io/someone/app:v1")
                    .build()
            )
          }
      assertEquals(Status.Code.INVALID_ARGUMENT, e.status.code)
    } finally {
      server.stop().join()
    }
  }

  @Test
  fun `create with a matching CAS token pins exactly the previewed digest`() = runBlocking {
    val resolver = ScriptedImageDigestResolver()
    val server = imageResolvingServer(resolver)
    server.start().join()
    try {
      val stub =
          GrpcClients.newClient(
              "gproto+http://127.0.0.1:${server.activeLocalPort()}/",
              FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
          )
      resolver.next = ImageResolution(ImageStatus.RESOLVED, digest = "sha256:confirmed")
      val created =
          stub.createProfile(
              CreateProfileRequest.newBuilder()
                  .setProfileId("cas-match")
                  .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                  .setDockerImage("us-docker.pkg.dev/p/repo/app:v1")
                  .setExpectedDockerImageDigest("sha256:confirmed")
                  .build()
          )
      assertEquals("sha256:confirmed", created.revision.dockerImageDigest)
    } finally {
      server.stop().join()
    }
  }

  @Test
  fun `create with a stale CAS token is rejected and creates nothing`() = runBlocking {
    val resolver = ScriptedImageDigestResolver()
    val server = imageResolvingServer(resolver)
    server.start().join()
    try {
      val stub =
          GrpcClients.newClient(
              "gproto+http://127.0.0.1:${server.activeLocalPort()}/",
              FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
          )
      // The admin confirmed :aaa, but the tag has since moved to :bbb.
      resolver.next = ImageResolution(ImageStatus.RESOLVED, digest = "sha256:bbb")
      val e =
          assertFailsWith<StatusException> {
            stub.createProfile(
                CreateProfileRequest.newBuilder()
                    .setProfileId("cas-stale")
                    .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                    .setDockerImage("us-docker.pkg.dev/p/repo/app:v1")
                    .setExpectedDockerImageDigest("sha256:aaa")
                    .build()
            )
          }
      assertEquals(Status.Code.FAILED_PRECONDITION, e.status.code)
      assertTrue("moved" in e.status.description!!, e.status.description!!)
      // The rejected CAS must leave no profile behind.
      assertTrue(stub.listProfiles(ListProfilesRequest.getDefaultInstance()).profilesList.isEmpty())
    } finally {
      server.stop().join()
    }
  }

  @Test
  fun `Re-verify never re-pins an already-resolved digest`() = runBlocking {
    val resolver = ScriptedImageDigestResolver()
    val server = imageResolvingServer(resolver)
    server.start().join()
    try {
      val stub =
          GrpcClients.newClient(
              "gproto+http://127.0.0.1:${server.activeLocalPort()}/",
              FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
          )
      resolver.next = ImageResolution(ImageStatus.RESOLVED, digest = "sha256:pinned")
      stub.createProfile(
          CreateProfileRequest.newBuilder()
              .setProfileId("immutable-pin")
              .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
              .setDockerImage("us-docker.pkg.dev/p/repo/app:v1")
              .build()
      )

      // The tag moves — but Re-verify must NOT change what this revision pinned.
      resolver.next = ImageResolution(ImageStatus.RESOLVED, digest = "sha256:moved")
      val verified =
          stub.verifyProfile(
              VerifyProfileRequest.newBuilder().setProfileId("immutable-pin").build()
          )
      assertEquals(
          "sha256:pinned",
          verified.revision.dockerImageDigest,
          "a resolved digest is immutable; Re-verify must not re-pin it",
      )
    } finally {
      server.stop().join()
    }
  }

  @Test
  fun `Re-verify fills in an unresolved image once the grant is fixed`() = runBlocking {
    val resolver = ScriptedImageDigestResolver()
    val server = imageResolvingServer(resolver)
    server.start().join()
    try {
      val stub =
          GrpcClients.newClient(
              "gproto+http://127.0.0.1:${server.activeLocalPort()}/",
              FleetServiceGrpcKt.FleetServiceCoroutineStub::class.java,
          )
      // Created while the reader grant was missing → nothing pinned.
      resolver.next = ImageResolution(ImageStatus.UNRESOLVABLE, detail = "fake: 403")
      val created =
          stub.createProfile(
              CreateProfileRequest.newBuilder()
                  .setProfileId("fixable")
                  .setTargetServiceAccount("sa@project.iam.gserviceaccount.com")
                  .setDockerImage("us-docker.pkg.dev/p/repo/app:v1")
                  .build()
          )
      assertEquals("", created.revision.dockerImageDigest)

      // Grant fixed; Re-verify now resolves it in place (nothing was pinned to protect).
      resolver.next = ImageResolution(ImageStatus.RESOLVED, digest = "sha256:nowreadable")
      val verified =
          stub.verifyProfile(VerifyProfileRequest.newBuilder().setProfileId("fixable").build())
      assertEquals(
          software.medusa.workload.v1.ImageStatus.IMAGE_STATUS_RESOLVED,
          verified.revision.imageStatus,
      )
      assertEquals("sha256:nowreadable", verified.revision.dockerImageDigest)
    } finally {
      server.stop().join()
    }
  }

  @Test
  fun `createEnrollmentToken returns a valid wle_ token once and stores only its hash`() =
      runBlocking {
        val response =
            stub.createEnrollmentToken(
                CreateEnrollmentTokenRequest.newBuilder()
                    .setNote("for kuba's mbp")
                    .setRequireApproval(true)
                    .build()
            )

        // The plaintext is a well-formed enrollment token, returned exactly once here.
        assertTrue(WorkloadToken.isValid(response.token, TokenKind.ENROLLMENT))
        assertEquals("for kuba's mbp", response.enrollmentToken.note)
        assertTrue(response.enrollmentToken.requireApproval)
        assertTrue(response.enrollmentToken.createdBy.isNotBlank())

        // The store holds only the hash; the token itself is not recoverable from it.
        val stored = fleetStore.listOutstandingEnrollmentTokens(java.time.Instant.now()).single()
        assertEquals(hashEnrollmentToken(response.token), stored.tokenHash)
        assertEquals(response.enrollmentToken.enrollmentTokenId, stored.id.value.toString())
      }

  @Test
  fun `createEnrollmentToken defaults to a 7-day expiry, honoring an explicit one`() = runBlocking {
    val now = java.time.Instant.now()

    val defaulted = stub.createEnrollmentToken(CreateEnrollmentTokenRequest.getDefaultInstance())
    val defaultExpiry = java.time.Instant.parse(defaulted.enrollmentToken.expiresAt)
    val defaultDays = java.time.Duration.between(now, defaultExpiry).toDays()
    assertEquals(7L, defaultDays)

    val explicit =
        stub.createEnrollmentToken(
            CreateEnrollmentTokenRequest.newBuilder().setExpiresInDays(2).build()
        )
    val explicitExpiry = java.time.Instant.parse(explicit.enrollmentToken.expiresAt)
    assertEquals(2L, java.time.Duration.between(now, explicitExpiry).toDays())
  }

  @Test
  fun `listEnrollmentTokens shows outstanding tokens and drops revoked ones`() = runBlocking {
    val a =
        stub.createEnrollmentToken(CreateEnrollmentTokenRequest.newBuilder().setNote("a").build())
    val b =
        stub.createEnrollmentToken(CreateEnrollmentTokenRequest.newBuilder().setNote("b").build())

    val before =
        stub
            .listEnrollmentTokens(ListEnrollmentTokensRequest.getDefaultInstance())
            .enrollmentTokensList
            .map { it.enrollmentTokenId }
    assertTrue(
        before.containsAll(
            listOf(a.enrollmentToken.enrollmentTokenId, b.enrollmentToken.enrollmentTokenId)
        )
    )

    stub.revokeEnrollmentToken(
        RevokeEnrollmentTokenRequest.newBuilder()
            .setEnrollmentTokenId(a.enrollmentToken.enrollmentTokenId)
            .build()
    )

    val after =
        stub
            .listEnrollmentTokens(ListEnrollmentTokensRequest.getDefaultInstance())
            .enrollmentTokensList
            .map { it.enrollmentTokenId }
    assertTrue(a.enrollmentToken.enrollmentTokenId !in after)
    assertTrue(b.enrollmentToken.enrollmentTokenId in after)
  }

  @Test
  fun `revokeEnrollmentToken on an unknown id fails with NOT_FOUND`() = runBlocking {
    val exception =
        assertFailsWith<StatusException> {
          stub.revokeEnrollmentToken(
              RevokeEnrollmentTokenRequest.newBuilder()
                  .setEnrollmentTokenId(java.util.UUID.randomUUID().toString())
                  .build()
          )
        }
    assertEquals(Status.Code.NOT_FOUND, exception.status.code)
  }

  private fun imageResolvingServer(resolver: ImageDigestResolver): Server =
      buildServer(
          originRegex = """http://localhost(:\d+)?""",
          port = 0,
          auth = NoOpAuthDecorator,
          fleetStore = InMemoryFleetStore(),
          impersonationVerifier = AlwaysVerifiedImpersonationVerifier,
          imageDigestResolver = resolver,
      )

  private suspend fun anActiveWorker(name: String): WorkerId {
    val worker =
        fleetStore.createWorker(
            NewWorker(
                hashWorkerSecret(WorkloadToken.generate(TokenKind.WORKER)),
                name,
                hostname = null,
                os = null,
                cliVersion = null,
            )
        )
    fleetStore.approveWorker(worker.workerId, approvedBy = "admin@example.com")
    return worker.workerId
  }

  @Test
  fun `revokeWorker returns the worker with revoked_at set`() = runBlocking {
    val workerId = anActiveWorker("to-revoke")
    val response =
        stub.revokeWorker(
            RevokeWorkerRequest.newBuilder().setWorkerId(workerId.value.toString()).build()
        )
    assertEquals(WorkerStatus.WORKER_STATUS_REVOKED, response.worker.status)
    assertTrue(response.worker.revokedAt.isNotBlank())
  }

  @Test
  fun `listRuns denormalizes the worker name and applies filters`() = runBlocking {
    val tux = anActiveWorker("tux-worker")
    val mac = anActiveWorker("jakub-mac")
    val tuxRun = fleetStore.createRun(NewRun(tux, ProfileId("flow-worker"), 4, RunKind.RUN))
    fleetStore.createRun(NewRun(mac, ProfileId("hand-test-1"), 2, RunKind.EXEC))
    val ended = fleetStore.createRun(NewRun(tux, ProfileId("flow-worker"), 4, RunKind.RUN))
    fleetStore.endRun(ended.runId, exitCode = 0)

    val all = stub.listRuns(ListRunsRequest.getDefaultInstance()).runsList
    assertEquals(3, all.size)
    val tuxProto = all.single { it.runId == tuxRun.runId.value.toString() }
    assertEquals("tux-worker", tuxProto.workerName)
    assertEquals("flow-worker", tuxProto.profileId)
    assertEquals(4, tuxProto.revision)
    assertEquals(RunStateProto.RUN_STATE_RUNNING, tuxProto.state)

    val byWorker =
        stub.listRuns(ListRunsRequest.newBuilder().setWorkerId(tux.value.toString()).build())
    assertEquals(2, byWorker.runsList.size)
    val byProfile = stub.listRuns(ListRunsRequest.newBuilder().setProfileId("hand-test-1").build())
    assertEquals(1, byProfile.runsList.size)
    val live = stub.listRuns(ListRunsRequest.newBuilder().setLiveOnly(true).build())
    assertEquals(2, live.runsList.size)
    assertTrue(live.runsList.none { it.runId == ended.runId.value.toString() })
  }

  @Test
  fun `listRuns reports the exit code presence flag`() = runBlocking {
    val workerId = anActiveWorker("exit-worker")
    val succeeded = fleetStore.createRun(NewRun(workerId, ProfileId("p-1"), 1, RunKind.RUN))
    fleetStore.endRun(succeeded.runId, exitCode = 0)
    val running = fleetStore.createRun(NewRun(workerId, ProfileId("p-1"), 1, RunKind.RUN))

    val byId = stub.listRuns(ListRunsRequest.getDefaultInstance()).runsList.associateBy { it.runId }
    // A run that ended with exit 0: hasExitCode distinguishes it from a running run's default 0.
    assertTrue(byId.getValue(succeeded.runId.value.toString()).hasExitCode)
    assertEquals(0, byId.getValue(succeeded.runId.value.toString()).exitCode)
    assertTrue(!byId.getValue(running.runId.value.toString()).hasExitCode)
  }
}

/** Reports success unless the revision references any secrets, which it always flags. */
private object SecretInaccessibleVerifier : ImpersonationVerifier {
  override suspend fun verify(
      targetServiceAccount: String,
      secretEnvVars: Map<String, String>,
  ): VerificationResult =
      if (secretEnvVars.isEmpty()) {
        VerificationResult(software.medusa.workload.server.VerificationStatus.VERIFIED)
      } else {
        VerificationResult(
            software.medusa.workload.server.VerificationStatus.SECRET_INACCESSIBLE,
            "fake: secret inaccessible",
        )
      }
}

/** Returns whatever [next] is set to on each call — lets a test script the resolution outcome. */
private class ScriptedImageDigestResolver : ImageDigestResolver {
  var next: ImageResolution = ImageResolution(ImageStatus.RESOLVED, digest = "sha256:default")

  override suspend fun resolve(targetServiceAccount: String, imageRef: String): ImageResolution =
      next
}
