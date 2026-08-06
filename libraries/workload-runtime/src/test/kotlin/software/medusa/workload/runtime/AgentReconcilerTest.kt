package software.medusa.workload.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import software.medusa.workload.docker.ContainerSummary

private fun runningContainer(profileId: String, revision: Int, id: String = "c-$profileId") =
    ContainerSummary(
        id = id,
        state = "running",
        labels =
            mapOf(
                workloadProfileLabel to profileId,
                workloadRevisionLabel to revision.toString(),
                workloadAgentManagedLabel to "true",
            ),
    )

private fun exitedContainer(profileId: String, revision: Int, id: String = "c-$profileId") =
    runningContainer(profileId, revision, id).copy(state = "exited")

private fun desired(profileId: String, revision: Int) =
    DesiredAssignment(profileId, revision, dockerImageDigest = "sha256:$profileId-$revision")

class AgentReconcilerTest {

  @Test
  fun `an unassigned profile with nothing running is started`() {
    val plan = diffAssignments(desired = listOf(desired("a", 1)), running = emptyList())
    assertEquals(listOf(desired("a", 1)), plan.toStart)
    assertTrue(plan.toStop.isEmpty())
  }

  @Test
  fun `a container already running at the assigned revision is adopted, not touched`() {
    val running = runningContainer("a", 1)
    val plan = diffAssignments(desired = listOf(desired("a", 1)), running = listOf(running))
    assertTrue(plan.toStart.isEmpty())
    assertTrue(plan.toStop.isEmpty())
  }

  @Test
  fun `a stale revision is stopped and a fresh one started`() {
    val running = runningContainer("a", 1)
    val plan = diffAssignments(desired = listOf(desired("a", 2)), running = listOf(running))
    assertEquals(listOf(desired("a", 2)), plan.toStart)
    assertEquals(listOf(running), plan.toStop)
  }

  @Test
  fun `a container for a no-longer-assigned profile is stopped`() {
    val running = runningContainer("a", 1)
    val plan = diffAssignments(desired = emptyList(), running = listOf(running))
    assertTrue(plan.toStart.isEmpty())
    assertEquals(listOf(running), plan.toStop)
  }

  @Test
  fun `an exited container at the right revision is still stopped and restarted`() {
    val exited = exitedContainer("a", 1)
    val plan = diffAssignments(desired = listOf(desired("a", 1)), running = listOf(exited))
    assertEquals(listOf(desired("a", 1)), plan.toStart)
    assertEquals(listOf(exited), plan.toStop)
  }

  @Test
  fun `pausing (an empty desired set) stops everything and starts nothing`() {
    val running = listOf(runningContainer("a", 1), runningContainer("b", 3))
    val plan = diffAssignments(desired = emptyList(), running = running)
    assertTrue(plan.toStart.isEmpty())
    assertEquals(running.toSet(), plan.toStop.toSet())
  }

  @Test
  fun `restarting the daemon against unchanged state adopts everything, no churn`() {
    val running = listOf(runningContainer("a", 1), runningContainer("b", 2))
    val desired = listOf(desired("a", 1), desired("b", 2))
    val plan = diffAssignments(desired, running)
    assertTrue(plan.toStart.isEmpty())
    assertTrue(plan.toStop.isEmpty())
  }
}
