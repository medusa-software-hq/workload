package software.medusa.workload.runtime

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import software.medusa.workload.docker.ContainerSummary

private val baseNow: Instant = Instant.parse("2026-01-01T00:00:00Z")

private fun runningContainer(
    profileId: String,
    revision: Int,
    digest: String = "sha256:$profileId-$revision",
    id: String = "c-$profileId",
    name: String = "$id",
    extraLabels: Map<String, String> = emptyMap(),
) =
    ContainerSummary(
        id = id,
        names = listOf("/$name"),
        state = "running",
        labels =
            mapOf(
                workloadProfileLabel to profileId,
                workloadRevisionLabel to revision.toString(),
                workloadImageDigestLabel to digest,
                workloadAgentManagedLabel to "true",
            ) + extraLabels,
    )

private fun exitedContainer(
    profileId: String,
    revision: Int,
    digest: String = "sha256:$profileId-$revision",
    id: String = "c-$profileId",
    exitCode: Int = 1,
) =
    runningContainer(profileId, revision, digest, id)
        .copy(state = "exited", status = "Exited ($exitCode) 2 minutes ago")

private fun desired(profileId: String, revision: Int, drainDeadline: kotlin.time.Duration? = null) =
    DesiredAssignment(
        profileId,
        revision,
        dockerImageDigest = "sha256:$profileId-$revision",
        drainDeadline,
    )

class PlanReconcileTest {

  @Test
  fun `an unassigned profile with nothing running is started`() {
    val plan =
        planReconcile(desired = listOf(desired("a", 1)), running = emptyList(), now = baseNow)
    assertEquals(listOf(desired("a", 1)), plan.toStart)
    assertTrue(plan.toRemove.isEmpty())
    assertTrue(plan.toBeginDrain.isEmpty())
  }

  @Test
  fun `a container already running at the assigned revision and digest is adopted, not touched`() {
    val running = runningContainer("a", 1)
    val plan =
        planReconcile(desired = listOf(desired("a", 1)), running = listOf(running), now = baseNow)
    assertTrue(plan.toStart.isEmpty())
    assertTrue(plan.toRemove.isEmpty())
    assertTrue(plan.toBeginDrain.isEmpty())
  }

  @Test
  fun `a stale revision begins draining instead of stopping immediately`() {
    val running = runningContainer("a", 1)
    val plan =
        planReconcile(desired = listOf(desired("a", 2)), running = listOf(running), now = baseNow)
    // Not started yet — the old one still occupies the profile's slot until it's actually drained.
    assertTrue(plan.toStart.isEmpty())
    assertEquals(1, plan.toBeginDrain.size)
    assertEquals(running, plan.toBeginDrain.single().container)
    assertEquals(desired("a", 2), plan.toBeginDrain.single().replacement)
  }

  @Test
  fun `a digest-only change at the same revision is non-conforming and begins draining`() {
    val running = runningContainer("a", 1, digest = "sha256:old")
    val d = desired("a", 1).copy(dockerImageDigest = "sha256:new")
    val plan = planReconcile(desired = listOf(d), running = listOf(running), now = baseNow)
    assertEquals(1, plan.toBeginDrain.size)
    assertEquals(d, plan.toBeginDrain.single().replacement)
  }

  @Test
  fun `a container for a no-longer-assigned profile begins draining with no replacement`() {
    val running = runningContainer("a", 1)
    val plan = planReconcile(desired = emptyList(), running = listOf(running), now = baseNow)
    assertTrue(plan.toStart.isEmpty())
    assertEquals(1, plan.toBeginDrain.size)
    assertNull(plan.toBeginDrain.single().replacement)
  }

  @Test
  fun `an exited container at the right revision is removed and restarted immediately, no drain`() {
    val exited = exitedContainer("a", 1)
    val plan =
        planReconcile(desired = listOf(desired("a", 1)), running = listOf(exited), now = baseNow)
    assertEquals(listOf(desired("a", 1)), plan.toStart)
    assertEquals(listOf(exited), plan.toRemove)
    assertTrue(plan.toBeginDrain.isEmpty())
  }

  @Test
  fun `an exited container still at its desired digest is a crashloop candidate`() {
    val exited = exitedContainer("a", 1)
    val plan =
        planReconcile(desired = listOf(desired("a", 1)), running = listOf(exited), now = baseNow)
    assertEquals(listOf(exited), plan.crashloopCandidates)
  }

  @Test
  fun `an exited stale-revision container is cleanup, not a crashloop candidate`() {
    val exited = exitedContainer("a", 1)
    val plan =
        planReconcile(desired = listOf(desired("a", 2)), running = listOf(exited), now = baseNow)
    assertEquals(listOf(exited), plan.toRemove)
    assertTrue(plan.crashloopCandidates.isEmpty())
  }

  @Test
  fun `pausing (an empty desired set) begins draining everything and starts nothing`() {
    val running = listOf(runningContainer("a", 1), runningContainer("b", 3))
    val plan = planReconcile(desired = emptyList(), running = running, now = baseNow)
    assertTrue(plan.toStart.isEmpty())
    assertEquals(running.toSet(), plan.toBeginDrain.map { it.container }.toSet())
    assertTrue(plan.toBeginDrain.all { it.replacement == null })
  }

  @Test
  fun `restarting the daemon against unchanged state adopts everything, no churn`() {
    val running = listOf(runningContainer("a", 1), runningContainer("b", 2))
    val desiredList = listOf(desired("a", 1), desired("b", 2))
    val plan = planReconcile(desiredList, running, baseNow)
    assertTrue(plan.toStart.isEmpty())
    assertTrue(plan.toRemove.isEmpty())
    assertTrue(plan.toBeginDrain.isEmpty())
  }

  @Test
  fun `a freshly-draining container is not force-stopped or replaced within its deadline`() {
    val startedAt = baseNow.minusSeconds(30)
    val running =
        runningContainer("a", 1, extraLabels = mapOf(workloadDrainDeadlineSecondsLabel to "3600"))
            .let { it.copy(names = listOf("/${it.id}--draining-${startedAt.epochSecond}")) }
    val plan = planReconcile(desired = emptyList(), running = listOf(running), now = baseNow)
    assertEquals(1, plan.draining.size)
    assertEquals(startedAt, plan.draining.single().since)
    assertTrue(plan.toForceStop.isEmpty())
    assertTrue(plan.toStart.isEmpty())
  }

  @Test
  fun `a container is force-stopped once its own deadline has lapsed`() {
    val startedAt = baseNow.minusSeconds(120)
    val running =
        runningContainer("a", 1, extraLabels = mapOf(workloadDrainDeadlineSecondsLabel to "60"))
            .let { it.copy(names = listOf("/${it.id}--draining-${startedAt.epochSecond}")) }
    val plan = planReconcile(desired = emptyList(), running = listOf(running), now = baseNow)
    assertEquals(listOf(running), plan.toForceStop)
    assertTrue(plan.draining.isEmpty())
  }

  @Test
  fun `a draining container's profile is not started even once a replacement is desired`() {
    val startedAt = baseNow.minusSeconds(30)
    val running =
        runningContainer("a", 1, extraLabels = mapOf(workloadDrainDeadlineSecondsLabel to "3600"))
            .let { it.copy(names = listOf("/${it.id}--draining-${startedAt.epochSecond}")) }
    val plan =
        planReconcile(desired = listOf(desired("a", 2)), running = listOf(running), now = baseNow)
    assertTrue(plan.toStart.isEmpty())
    assertEquals(1, plan.draining.size)
  }

  @Test
  fun `adopting a draining container on restart resumes waiting on the same deadline`() {
    // Simulates: agent A begins the drain (renames the container), then a fresh agent process B
    // computes the plan from scratch against the same Docker state — same bucket, same deadline,
    // no re-signal.
    val deadline = 6.hours
    val startedAt = baseNow.minusSeconds(10)
    val original =
        runningContainer(
            "a",
            1,
            extraLabels =
                mapOf(workloadDrainDeadlineSecondsLabel to deadline.inWholeSeconds.toString()),
        )
    val renamed = original.copy(names = listOf("/${drainingContainerName(original, startedAt)}"))
    val plan = planReconcile(desired = emptyList(), running = listOf(renamed), now = baseNow)
    assertEquals(1, plan.draining.size)
    assertEquals(startedAt, plan.draining.single().since)
    assertEquals(deadline, plan.draining.single().deadline)
  }

  @Test
  fun `default drain deadline applies when the container has no drain-deadline label`() {
    val startedAt = baseNow.minusSeconds(5)
    val running =
        runningContainer("a", 1).let {
          it.copy(names = listOf("/${it.id}--draining-${startedAt.epochSecond}"))
        }
    val plan =
        planReconcile(
            desired = emptyList(),
            running = listOf(running),
            now = baseNow,
            defaultDrainDeadline = 45.seconds,
        )
    assertEquals(45.seconds, plan.draining.single().deadline)
  }
}

class AssignmentStatusSummaryTest {
  @Test
  fun `a converged profile reports CONVERGED`() {
    val statuses =
        summarizeAssignmentStatuses(
            desired = listOf(desired("a", 1)),
            running = listOf(runningContainer("a", 1)),
            now = baseNow,
        )
    assertEquals(AgentAssignmentState.CONVERGED, statuses.single().state)
  }

  @Test
  fun `a draining profile reports DRAINING with since and deadline`() {
    val startedAt = baseNow.minusSeconds(30)
    val running =
        runningContainer("a", 1, extraLabels = mapOf(workloadDrainDeadlineSecondsLabel to "300"))
            .let { it.copy(names = listOf("/${it.id}--draining-${startedAt.epochSecond}")) }
    val statuses =
        summarizeAssignmentStatuses(
            desired = listOf(desired("a", 2)),
            running = listOf(running),
            now = baseNow,
        )
    val status = statuses.single()
    assertEquals(AgentAssignmentState.DRAINING, status.state)
    assertEquals(startedAt, status.since)
    assertEquals(startedAt.plusSeconds(300), status.drainDeadline)
  }

  @Test
  fun `a held profile reports CRASHLOOP_HOLD regardless of container state`() {
    val heldSince = mapOf("a" to baseNow.minusSeconds(600))
    val statuses =
        summarizeAssignmentStatuses(
            desired = listOf(desired("a", 1)),
            running = emptyList(),
            now = baseNow,
            heldSince = heldSince,
        )
    assertEquals(AgentAssignmentState.CRASHLOOP_HOLD, statuses.single().state)
    assertEquals(baseNow.minusSeconds(600), statuses.single().since)
  }

  @Test
  fun `a profile with nothing running yet reports REPLACING`() {
    val statuses =
        summarizeAssignmentStatuses(
            desired = listOf(desired("a", 1)),
            running = emptyList(),
            now = baseNow,
        )
    assertEquals(AgentAssignmentState.REPLACING, statuses.single().state)
  }
}

class ParseDrainDeadlineTest {
  @Test
  fun `parses simple duration strings`() {
    assertEquals(6.hours, parseDrainDeadline("6h"))
    assertEquals(90.minutes, parseDrainDeadline("90m"))
  }

  @Test
  fun `blank or malformed input yields null`() {
    assertNull(parseDrainDeadline(null))
    assertNull(parseDrainDeadline(""))
    assertNull(parseDrainDeadline("not a duration"))
  }
}
