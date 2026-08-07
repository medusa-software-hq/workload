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

  private fun test(
      slotCapacity: Int,
      block: suspend (FleetStore, FallbackPlacementReconciler) -> Unit,
  ) = runBlocking {
    val store = InMemoryFleetStore()
    block(store, FallbackPlacementReconciler(store, slotCapacity))
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

  // Slot capacity + FIFO overflow (workload#122 part 3).

  @Test
  fun `a placement beyond slot capacity is queued -- recorded as an assignment but not granted`() =
      test(slotCapacity = 1) { store, reconciler ->
        val fallback = activeWorker(store, "fallback")
        store.setWorkerFallbackNode(fallback, fallbackNode = true)
        val first = profile(store, "profile-1")
        val second = profile(store, "profile-2")
        store.setProfileFallbackEligible(first, fallbackEligible = true)
        store.setProfileFallbackEligible(second, fallbackEligible = true)

        reconciler.reconcile(first)
        reconciler.reconcile(second)

        assertTrue(store.hasGrant(fallback, first))
        assertTrue(!store.hasGrant(fallback, second))
        // Still placed (queued) on the fallback node, just not granted a slot yet.
        assertEquals(fallback, store.fallbackAssignment(second)?.workerId)
      }

  @Test
  fun `admits the oldest queued profile once a slot frees`() =
      test(slotCapacity = 1) { store, reconciler ->
        val fallback = activeWorker(store, "fallback")
        store.setWorkerFallbackNode(fallback, fallbackNode = true)
        val first = profile(store, "profile-1")
        val second = profile(store, "profile-2")
        store.setProfileFallbackEligible(first, fallbackEligible = true)
        store.setProfileFallbackEligible(second, fallbackEligible = true)
        reconciler.reconcile(first)
        reconciler.reconcile(second)
        assertTrue(!store.hasGrant(fallback, second))

        store.setProfileFallbackEligible(first, fallbackEligible = false)
        reconciler.reconcile(first)

        assertNull(store.fallbackAssignment(first))
        assertTrue(store.hasGrant(fallback, second))
      }

  @Test
  fun `a dedicated node appearing for a queued profile frees nothing for the next in line`() =
      test(slotCapacity = 1) { store, reconciler ->
        val fallback = activeWorker(store, "fallback")
        store.setWorkerFallbackNode(fallback, fallbackNode = true)
        val first = profile(store, "profile-1")
        val second = profile(store, "profile-2")
        store.setProfileFallbackEligible(first, fallbackEligible = true)
        store.setProfileFallbackEligible(second, fallbackEligible = true)
        reconciler.reconcile(first)
        reconciler.reconcile(second)

        // The queued profile gets a dedicated node instead of ever reaching the fallback node.
        val dedicated = activeWorker(store, "dedicated")
        store.grant(dedicated, second, grantedBy = "admin@example.com")
        reconciler.reconcile(second)

        assertNull(store.fallbackAssignment(second))
        assertTrue(store.hasGrant(dedicated, second))
        // The slot occupant is unaffected.
        assertTrue(store.hasGrant(fallback, first))
      }

  @Test
  fun `drains multiple queued profiles strictly in arrival order as slots free one at a time`() =
      test(slotCapacity = 2) { store, reconciler ->
        val fallback = activeWorker(store, "fallback")
        store.setWorkerFallbackNode(fallback, fallbackNode = true)
        val p1 = profile(store, "profile-1")
        val p2 = profile(store, "profile-2")
        val p3 = profile(store, "profile-3")
        listOf(p1, p2, p3).forEach { store.setProfileFallbackEligible(it, fallbackEligible = true) }

        reconciler.reconcile(p1)
        reconciler.reconcile(p2)
        reconciler.reconcile(p3)

        assertTrue(store.hasGrant(fallback, p1))
        assertTrue(store.hasGrant(fallback, p2))
        assertTrue(!store.hasGrant(fallback, p3))

        store.setProfileFallbackEligible(p1, fallbackEligible = false)
        reconciler.reconcile(p1)

        // p3 was next in line, ahead of any later arrival; p2's slot is untouched.
        assertTrue(store.hasGrant(fallback, p2))
        assertTrue(store.hasGrant(fallback, p3))
      }

  @Test
  fun `reconcileAll grants up to capacity and leaves the rest queued`() =
      test(slotCapacity = 2) { store, reconciler ->
        val fallback = activeWorker(store, "fallback")
        store.setWorkerFallbackNode(fallback, fallbackNode = true)
        val p1 = profile(store, "profile-1")
        val p2 = profile(store, "profile-2")
        val p3 = profile(store, "profile-3")
        listOf(p1, p2, p3).forEach { store.setProfileFallbackEligible(it, fallbackEligible = true) }

        reconciler.reconcileAll()

        val granted = listOf(p1, p2, p3).count { store.hasGrant(fallback, it) }
        assertEquals(2, granted)
        // Every eligible profile is at least placed, granted or not.
        listOf(p1, p2, p3).forEach {
          assertEquals(fallback, store.fallbackAssignment(it)?.workerId)
        }
      }
}
