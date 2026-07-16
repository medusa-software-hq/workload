package software.medusa.workload.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Behavior every [FleetStore] implementation must satisfy, run against both [InMemoryFleetStore]
 * ([InMemoryFleetStoreTest]) and [PostgresFleetStore] ([PostgresFleetStoreTest]).
 */
abstract class FleetStoreContractTest {
  abstract fun createStore(): FleetStore

  private fun test(block: suspend (FleetStore) -> Unit) = runBlocking { block(createStore()) }

  private fun newWorker(name: String = "worker-1") =
      NewWorker(
          secretHash = hashWorkerSecret(generateWorkerSecret()),
          name = name,
          hostname = "host.local",
          os = "linux",
          cliVersion = "1.0.0",
          confirmationCode = "1234",
      )

  @Test
  fun `a newly created worker is pending`() = test { store ->
    val worker = store.createWorker(newWorker())
    assertEquals(WorkerStatus.PENDING, worker.status)
    assertNull(worker.approvedAt)
    assertNull(worker.approvedBy)
  }

  @Test
  fun `getWorker returns null for an unknown id`() = test { store ->
    assertNull(store.getWorker(WorkerId(java.util.UUID.randomUUID())))
  }

  @Test
  fun `listWorkers returns every created worker`() = test { store ->
    val first = store.createWorker(newWorker(name = "worker-a"))
    val second = store.createWorker(newWorker(name = "worker-b"))

    val ids = store.listWorkers().map { it.workerId }
    assertTrue(ids.containsAll(listOf(first.workerId, second.workerId)))
  }

  @Test
  fun `getWorker round-trips what createWorker returned`() = test { store ->
    val created = store.createWorker(newWorker())
    assertEquals(created, store.getWorker(created.workerId))
  }

  @Test
  fun `approveWorker moves pending to active and records who approved it`() = test { store ->
    val created = store.createWorker(newWorker())
    val approved = store.approveWorker(created.workerId, approvedBy = "admin@example.com")
    assertNotNull(approved)
    assertEquals(WorkerStatus.ACTIVE, approved.status)
    assertEquals("admin@example.com", approved.approvedBy)
    assertNotNull(approved.approvedAt)
  }

  @Test
  fun `approveWorker on an unknown id returns null`() = test { store ->
    assertNull(
        store.approveWorker(WorkerId(java.util.UUID.randomUUID()), approvedBy = "admin@example.com")
    )
  }

  @Test
  fun `rejectWorker moves a worker to rejected`() = test { store ->
    val created = store.createWorker(newWorker())
    val rejected = store.rejectWorker(created.workerId)
    assertEquals(WorkerStatus.REJECTED, rejected?.status)
  }

  @Test
  fun `revokeWorker moves a worker to revoked`() = test { store ->
    val created = store.createWorker(newWorker())
    store.approveWorker(created.workerId, approvedBy = "admin@example.com")
    val revoked = store.revokeWorker(created.workerId)
    assertEquals(WorkerStatus.REVOKED, revoked?.status)
  }

  @Test
  fun `touchLastSeen sets lastSeenAt on the worker`() = test { store ->
    val created = store.createWorker(newWorker())
    assertNull(created.lastSeenAt)

    store.touchLastSeen(created.workerId)

    assertNotNull(store.getWorker(created.workerId)?.lastSeenAt)
  }

  @Test
  fun `touchLastSeen on an unknown id is a no-op`() = test { store ->
    store.touchLastSeen(WorkerId(java.util.UUID.randomUUID()))
  }

  @Test
  fun `worker secrets are never persisted in plaintext`() = test { store ->
    val secret = generateWorkerSecret()
    val worker = store.createWorker(newWorker().copy(secretHash = hashWorkerSecret(secret)))
    val stored = store.getWorker(worker.workerId)
    assertNotNull(stored)
    assertFalse(stored.secretHash.bytes.contentEquals(secret.toByteArray()))
    assertTrue(verifyWorkerSecret(secret, stored.secretHash))
    assertFalse(verifyWorkerSecret("wrong-secret", stored.secretHash))
  }

  @Test
  fun `createProfile creates revision 1 and sets it as latest`() = test { store ->
    val profile =
        store.createProfile(
            ProfileId("my-profile-1"),
            displayName = "My Profile",
            revision =
                NewProfileRevision(
                    "sa@project.iam.gserviceaccount.com",
                    createdBy = "admin@example.com",
                ),
        )
    assertEquals(1, profile.latestRevision)
    assertFalse(profile.archived)

    val latest = store.getLatestProfileRevision(profile.profileId)
    assertEquals(1, latest?.revision)
    assertEquals("sa@project.iam.gserviceaccount.com", latest?.targetServiceAccount)
    assertEquals(VerificationStatus.UNVERIFIED, latest?.verificationStatus)
  }

  @Test
  fun `env vars and secret env vars round-trip through createProfile and appendProfileRevision`() =
      test { store ->
        val profileId = ProfileId("my-profile-env")
        store.createProfile(
            profileId,
            displayName = null,
            revision =
                NewProfileRevision(
                    "sa@project.iam.gserviceaccount.com",
                    createdBy = "admin@example.com",
                    envVars = mapOf("MODE" to "batch"),
                    secretEnvVars =
                        mapOf("API_KEY" to "projects/p/secrets/api-key/versions/latest"),
                ),
        )

        val firstRevision = store.getLatestProfileRevision(profileId)
        assertEquals(mapOf("MODE" to "batch"), firstRevision?.envVars)
        assertEquals(
            mapOf("API_KEY" to "projects/p/secrets/api-key/versions/latest"),
            firstRevision?.secretEnvVars,
        )

        store.appendProfileRevision(
            profileId,
            NewProfileRevision(
                "sa@project.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
                envVars = mapOf("MODE" to "streaming", "RETRIES" to "3"),
                secretEnvVars = emptyMap(),
            ),
        )

        val secondRevision = store.getLatestProfileRevision(profileId)
        assertEquals(mapOf("MODE" to "streaming", "RETRIES" to "3"), secondRevision?.envVars)
        assertEquals(emptyMap(), secondRevision?.secretEnvVars)

        // The first revision must stay exactly as it was — appends never mutate history.
        val revisions = store.listProfileRevisions(profileId).associateBy { it.revision }
        assertEquals(mapOf("MODE" to "batch"), revisions[1]?.envVars)
      }

  @Test
  fun `recordVerification updates only the targeted revision`() = test { store ->
    val profileId = ProfileId("my-profile-verify")
    store.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                "sa-v1@project.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
            ),
    )
    store.appendProfileRevision(
        profileId,
        NewProfileRevision(
            "sa-v2@project.iam.gserviceaccount.com",
            createdBy = "admin@example.com",
        ),
    )

    val updated = store.recordVerification(profileId, revision = 1, VerificationStatus.VERIFIED)
    assertEquals(VerificationStatus.VERIFIED, updated?.verificationStatus)

    val revisions = store.listProfileRevisions(profileId).associateBy { it.revision }
    assertEquals(VerificationStatus.VERIFIED, revisions[1]?.verificationStatus)
    assertEquals(VerificationStatus.UNVERIFIED, revisions[2]?.verificationStatus)
  }

  @Test
  fun `recordVerification on an unknown revision returns null`() = test { store ->
    assertNull(
        store.recordVerification(
            ProfileId("no-such-profile"),
            revision = 1,
            VerificationStatus.VERIFIED,
        )
    )
  }

  @Test
  fun `docker image round-trips and starts UNDETERMINED, exec profiles stay NOT_APPLICABLE`() =
      test { store ->
        val imageProfile = ProfileId("my-profile-image")
        store.createProfile(
            imageProfile,
            displayName = null,
            revision =
                NewProfileRevision(
                    "sa@project.iam.gserviceaccount.com",
                    createdBy = "admin@example.com",
                    dockerImage = "us-docker.pkg.dev/p/repo/app:v1",
                ),
        )
        val withImage = store.getLatestProfileRevision(imageProfile)
        assertEquals("us-docker.pkg.dev/p/repo/app:v1", withImage?.dockerImage)
        assertNull(withImage?.dockerImageDigest)
        // Has an image but not yet resolved → pending, not NOT_APPLICABLE.
        assertEquals(ImageStatus.UNDETERMINED, withImage?.imageStatus)

        val execProfile = ProfileId("my-profile-exec")
        store.createProfile(
            execProfile,
            displayName = null,
            revision =
                NewProfileRevision(
                    "sa@project.iam.gserviceaccount.com",
                    createdBy = "admin@example.com",
                ),
        )
        val exec = store.getLatestProfileRevision(execProfile)
        assertNull(exec?.dockerImage)
        assertEquals(ImageStatus.NOT_APPLICABLE, exec?.imageStatus)
      }

  @Test
  fun `recordImageDigest pins the digest on only the targeted revision`() = test { store ->
    val profileId = ProfileId("my-profile-digest")
    store.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                "sa@project.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
                dockerImage = "us-docker.pkg.dev/p/repo/app:v1",
            ),
    )
    store.appendProfileRevision(
        profileId,
        NewProfileRevision(
            "sa@project.iam.gserviceaccount.com",
            createdBy = "admin@example.com",
            dockerImage = "us-docker.pkg.dev/p/repo/app:v2",
        ),
    )

    val updated =
        store.recordImageDigest(
            profileId,
            revision = 1,
            digest = "sha256:abc",
            ImageStatus.RESOLVED,
        )
    assertEquals("sha256:abc", updated?.dockerImageDigest)
    assertEquals(ImageStatus.RESOLVED, updated?.imageStatus)

    val revisions = store.listProfileRevisions(profileId).associateBy { it.revision }
    assertEquals("sha256:abc", revisions[1]?.dockerImageDigest)
    assertEquals(ImageStatus.RESOLVED, revisions[1]?.imageStatus)
    // Revision 2 is untouched — still awaiting its own resolution.
    assertNull(revisions[2]?.dockerImageDigest)
    assertEquals(ImageStatus.UNDETERMINED, revisions[2]?.imageStatus)
  }

  @Test
  fun `recordImageDigest on an unknown revision returns null`() = test { store ->
    assertNull(
        store.recordImageDigest(
            ProfileId("no-such-profile"),
            revision = 1,
            digest = "sha256:abc",
            ImageStatus.RESOLVED,
        )
    )
  }

  @Test
  fun `appendProfileRevision atomically bumps latestRevision`() = test { store ->
    val profileId = ProfileId("my-profile-2")
    store.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                "sa-v1@project.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
            ),
    )

    val appended =
        store.appendProfileRevision(
            profileId,
            NewProfileRevision(
                "sa-v2@project.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
            ),
        )

    assertEquals(2, appended.revision)
    assertEquals(2, store.getProfile(profileId)?.latestRevision)
    assertEquals(
        "sa-v2@project.iam.gserviceaccount.com",
        store.getLatestProfileRevision(profileId)?.targetServiceAccount,
    )
  }

  @Test
  fun `listProfileRevisions returns every revision in order`() = test { store ->
    val profileId = ProfileId("my-profile-3")
    store.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                "sa-v1@project.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
            ),
    )
    store.appendProfileRevision(
        profileId,
        NewProfileRevision(
            "sa-v2@project.iam.gserviceaccount.com",
            createdBy = "admin@example.com",
        ),
    )
    store.appendProfileRevision(
        profileId,
        NewProfileRevision(
            "sa-v3@project.iam.gserviceaccount.com",
            createdBy = "admin@example.com",
        ),
    )

    val revisions = store.listProfileRevisions(profileId)
    assertEquals(listOf(1, 2, 3), revisions.map { it.revision })
  }

  @Test
  fun `listProfiles returns every created profile`() = test { store ->
    store.createProfile(
        ProfileId("profile-a"),
        displayName = null,
        revision =
            NewProfileRevision(
                "sa@project.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
            ),
    )
    store.createProfile(
        ProfileId("profile-b"),
        displayName = null,
        revision =
            NewProfileRevision(
                "sa@project.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
            ),
    )

    val profileIds = store.listProfiles().map { it.profileId.value }
    assertTrue(profileIds.containsAll(listOf("profile-a", "profile-b")))
  }

  @Test
  fun `grant then revoke round-trips hasGrant`() = test { store ->
    val worker = store.createWorker(newWorker())
    val profile =
        store.createProfile(
            ProfileId("my-profile-4"),
            displayName = null,
            revision =
                NewProfileRevision(
                    "sa@project.iam.gserviceaccount.com",
                    createdBy = "admin@example.com",
                ),
        )

    assertFalse(store.hasGrant(worker.workerId, profile.profileId))

    store.grant(worker.workerId, profile.profileId, grantedBy = "admin@example.com")
    assertTrue(store.hasGrant(worker.workerId, profile.profileId))

    store.revoke(worker.workerId, profile.profileId)
    assertFalse(store.hasGrant(worker.workerId, profile.profileId))
  }

  @Test
  fun `listGrantedProfileIds reflects grant and revoke`() = test { store ->
    val worker = store.createWorker(newWorker())
    val profileA =
        store.createProfile(
            ProfileId("granted-a"),
            displayName = null,
            revision =
                NewProfileRevision(
                    "sa@project.iam.gserviceaccount.com",
                    createdBy = "admin@example.com",
                ),
        )
    val profileB =
        store.createProfile(
            ProfileId("granted-b"),
            displayName = null,
            revision =
                NewProfileRevision(
                    "sa@project.iam.gserviceaccount.com",
                    createdBy = "admin@example.com",
                ),
        )

    assertEquals(emptyList(), store.listGrantedProfileIds(worker.workerId))

    store.grant(worker.workerId, profileA.profileId, grantedBy = "admin@example.com")
    store.grant(worker.workerId, profileB.profileId, grantedBy = "admin@example.com")
    assertEquals(
        listOf("granted-a", "granted-b"),
        store.listGrantedProfileIds(worker.workerId).map { it.value },
    )

    store.revoke(worker.workerId, profileA.profileId)
    assertEquals(listOf("granted-b"), store.listGrantedProfileIds(worker.workerId).map { it.value })
  }

  @Test
  fun `granting an already-granted pair does not error`() = test { store ->
    val worker = store.createWorker(newWorker())
    val profile =
        store.createProfile(
            ProfileId("my-profile-5"),
            displayName = null,
            revision =
                NewProfileRevision(
                    "sa@project.iam.gserviceaccount.com",
                    createdBy = "admin@example.com",
                ),
        )

    store.grant(worker.workerId, profile.profileId, grantedBy = "admin@example.com")
    store.grant(worker.workerId, profile.profileId, grantedBy = "admin2@example.com")
    assertTrue(store.hasGrant(worker.workerId, profile.profileId))
  }

  @Test
  fun `FleetStore exposes no way to mutate or delete an existing revision`() {
    val methodNames = FleetStore::class.java.methods.map { it.name.lowercase() }
    assertTrue(
        methodNames.none {
          it.contains("revision") &&
              (it.contains("update") || it.contains("delete") || it.contains("remove"))
        }
    )
  }
}
