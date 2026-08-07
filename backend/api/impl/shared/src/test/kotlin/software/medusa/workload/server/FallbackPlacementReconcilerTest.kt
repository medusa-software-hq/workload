package software.medusa.workload.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.workload.tokenformat.TokenKind
import software.medusa.workload.tokenformat.WorkloadToken

/** Unit coverage of [FallbackPlacementReconciler]'s placement/migration logic. */
class FallbackPlacementReconcilerTest {

  private fun test(block: suspend (FleetStore, FallbackPlacementReconciler) -> Unit) = runBlocking {
    val store = InMemoryFleetStore()
    block(store, FallbackPlacementReconciler(store))
  }

  private suspend fun activeWorker(store: FleetStore, name: String): WorkerId {
    val worker =
        store.createWorker(
            NewWorker(
                secretHash = hashWorkerSecret(WorkloadToken.generate(TokenKind.WORKER)),
                name = name,
                hostname = null,
                os = null,
                cliVersion = null,
            )
        )
    store.approveWorker(worker.workerId, approvedBy = "admin@example.com")
    return worker.workerId
  }

  private suspend fun profile(store: FleetStore, id: String): ProfileId {
    val profileId = ProfileId(id)
    store.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                targetServiceAccount = "sa@example.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
            ),
    )
    return profileId
  }

  private suspend fun FleetStore.fallbackAssignment(profileId: ProfileId) =
      listAssignments().find { it.profileId == profileId && it.createdBy == fallbackPlacementActor }

  @Test
  fun `a non-fallback-eligible profile is never placed on the fallback node`() =
      test { store, reconciler ->
        val fallback = activeWorker(store, "fallback")
        store.setWorkerFallbackNode(fallback, fallbackNode = true)
        val profileId = profile(store, "profile-1")

        reconciler.reconcile(profileId)

        assertNull(store.fallbackAssignment(profileId))
        assertTrue(!store.hasGrant(fallback, profileId))
      }

  @Test
  fun `lands on the fallback node when no dedicated node is present`() = test { store, reconciler ->
    val fallback = activeWorker(store, "fallback")
    store.setWorkerFallbackNode(fallback, fallbackNode = true)
    val profileId = profile(store, "profile-1")
    store.setProfileFallbackEligible(profileId, fallbackEligible = true)

    reconciler.reconcile(profileId)

    val assignment = store.fallbackAssignment(profileId)
    assertEquals(fallback, assignment?.workerId)
    assertTrue(store.hasGrant(fallback, profileId))
  }

  @Test
  fun `migrates off the fallback node once a dedicated grant appears`() =
      test { store, reconciler ->
        val fallback = activeWorker(store, "fallback")
        store.setWorkerFallbackNode(fallback, fallbackNode = true)
        val profileId = profile(store, "profile-1")
        store.setProfileFallbackEligible(profileId, fallbackEligible = true)
        reconciler.reconcile(profileId)
        assertTrue(store.hasGrant(fallback, profileId))

        val dedicated = activeWorker(store, "dedicated")
        store.grant(dedicated, profileId, grantedBy = "admin@example.com")
        reconciler.reconcile(profileId)

        assertNull(store.fallbackAssignment(profileId))
        assertTrue(!store.hasGrant(fallback, profileId))
        assertTrue(store.hasGrant(dedicated, profileId))
      }

  @Test
  fun `migrates off the fallback node once a dedicated first-class assignment appears`() =
      test { store, reconciler ->
        val fallback = activeWorker(store, "fallback")
        store.setWorkerFallbackNode(fallback, fallbackNode = true)
        val profileId = profile(store, "profile-1")
        store.setProfileFallbackEligible(profileId, fallbackEligible = true)
        reconciler.reconcile(profileId)

        val dedicated = activeWorker(store, "dedicated")
        store.createAssignment(NewAssignment(dedicated, profileId, createdBy = "admin@example.com"))
        reconciler.reconcile(profileId)

        assertNull(store.fallbackAssignment(profileId))
        assertTrue(!store.hasGrant(fallback, profileId))
      }

  @Test
  fun `falls back again once the dedicated node's grant is revoked`() = test { store, reconciler ->
    val fallback = activeWorker(store, "fallback")
    store.setWorkerFallbackNode(fallback, fallbackNode = true)
    val profileId = profile(store, "profile-1")
    store.setProfileFallbackEligible(profileId, fallbackEligible = true)
    val dedicated = activeWorker(store, "dedicated")
    store.grant(dedicated, profileId, grantedBy = "admin@example.com")
    reconciler.reconcile(profileId)
    assertTrue(!store.hasGrant(fallback, profileId))

    store.revoke(dedicated, profileId)
    reconciler.reconcile(profileId)

    assertTrue(store.hasGrant(fallback, profileId))
    assertEquals(fallback, store.fallbackAssignment(profileId)?.workerId)
  }

  @Test
  fun `falls back again once the dedicated node is revoked`() = test { store, reconciler ->
    val fallback = activeWorker(store, "fallback")
    store.setWorkerFallbackNode(fallback, fallbackNode = true)
    val profileId = profile(store, "profile-1")
    store.setProfileFallbackEligible(profileId, fallbackEligible = true)
    val dedicated = activeWorker(store, "dedicated")
    store.grant(dedicated, profileId, grantedBy = "admin@example.com")
    reconciler.reconcile(profileId)
    assertTrue(!store.hasGrant(fallback, profileId))

    store.revokeWorker(dedicated)
    reconciler.reconcile(profileId)

    assertTrue(store.hasGrant(fallback, profileId))
  }

  @Test
  fun `untagging fallback-eligible removes the fallback placement`() = test { store, reconciler ->
    val fallback = activeWorker(store, "fallback")
    store.setWorkerFallbackNode(fallback, fallbackNode = true)
    val profileId = profile(store, "profile-1")
    store.setProfileFallbackEligible(profileId, fallbackEligible = true)
    reconciler.reconcile(profileId)
    assertTrue(store.hasGrant(fallback, profileId))

    store.setProfileFallbackEligible(profileId, fallbackEligible = false)
    reconciler.reconcile(profileId)

    assertNull(store.fallbackAssignment(profileId))
    assertTrue(!store.hasGrant(fallback, profileId))
  }

  @Test
  fun `archiving a profile removes its fallback placement`() = test { store, reconciler ->
    val fallback = activeWorker(store, "fallback")
    store.setWorkerFallbackNode(fallback, fallbackNode = true)
    val profileId = profile(store, "profile-1")
    store.setProfileFallbackEligible(profileId, fallbackEligible = true)
    reconciler.reconcile(profileId)

    store.archiveProfile(profileId)
    reconciler.reconcile(profileId)

    assertNull(store.fallbackAssignment(profileId))
    assertTrue(!store.hasGrant(fallback, profileId))
  }

  @Test
  fun `with no active fallback node, a fallback-eligible profile simply has no placement`() =
      test { store, reconciler ->
        val profileId = profile(store, "profile-1")
        store.setProfileFallbackEligible(profileId, fallbackEligible = true)

        reconciler.reconcile(profileId)

        assertNull(store.fallbackAssignment(profileId))
      }

  @Test
  fun `reconcileAll re-lands every fallback-eligible profile once a fallback node comes online`() =
      test { store, reconciler ->
        val p1 = profile(store, "profile-1")
        val p2 = profile(store, "profile-2")
        store.setProfileFallbackEligible(p1, fallbackEligible = true)
        store.setProfileFallbackEligible(p2, fallbackEligible = true)
        reconciler.reconcile(p1)
        reconciler.reconcile(p2)

        val fallback = activeWorker(store, "fallback")
        store.setWorkerFallbackNode(fallback, fallbackNode = true)
        reconciler.reconcileAll()

        assertTrue(store.hasGrant(fallback, p1))
        assertTrue(store.hasGrant(fallback, p2))
      }

  @Test
  fun `picks the oldest active flagged worker when more than one is a fallback node`() =
      test { store, reconciler ->
        val older = activeWorker(store, "older-fallback")
        store.setWorkerFallbackNode(older, fallbackNode = true)
        val newer = activeWorker(store, "newer-fallback")
        store.setWorkerFallbackNode(newer, fallbackNode = true)
        val profileId = profile(store, "profile-1")
        store.setProfileFallbackEligible(profileId, fallbackEligible = true)

        reconciler.reconcile(profileId)

        assertEquals(older, store.fallbackAssignment(profileId)?.workerId)
      }

  @Test
  fun `moves the fallback assignment when the fallback node itself changes`() =
      test { store, reconciler ->
        val first = activeWorker(store, "first-fallback")
        store.setWorkerFallbackNode(first, fallbackNode = true)
        val profileId = profile(store, "profile-1")
        store.setProfileFallbackEligible(profileId, fallbackEligible = true)
        reconciler.reconcile(profileId)
        assertTrue(store.hasGrant(first, profileId))

        store.setWorkerFallbackNode(first, fallbackNode = false)
        val second = activeWorker(store, "second-fallback")
        store.setWorkerFallbackNode(second, fallbackNode = true)
        reconciler.reconcile(profileId)

        assertTrue(!store.hasGrant(first, profileId))
        assertTrue(store.hasGrant(second, profileId))
        assertEquals(second, store.fallbackAssignment(profileId)?.workerId)
      }
}
