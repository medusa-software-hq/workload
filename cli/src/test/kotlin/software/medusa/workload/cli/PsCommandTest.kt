package software.medusa.workload.cli

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import software.medusa.workload.docker.ContainerSummary
import software.medusa.workload.runtime.workloadProfileLabel
import software.medusa.workload.runtime.workloadRevisionLabel

/** Pure rendering/projection tests for `workload ps` — no daemon needed. */
class PsCommandTest {

  private val now = Instant.parse("2026-07-17T12:00:00Z")

  private fun container(
      id: String = "abcdef0123456789",
      state: String = "running",
      status: String = "Up 3 minutes",
      created: Long = now.epochSecond - 180,
      labels: Map<String, String> =
          mapOf(workloadProfileLabel to "my-profile-1", workloadRevisionLabel to "2"),
  ) = ContainerSummary(id = id, state = state, status = status, created = created, labels = labels)

  @Test
  fun `a row is projected from the workload labels`() {
    val row = toPsRow(container(), now)

    assertEquals("abcdef012345", row.id, "the id is shortened the way docker ps does")
    assertEquals("my-profile-1", row.profile)
    assertEquals("2", row.revision)
    assertEquals("running", row.state)
    assertEquals("Up 3 minutes", row.status)
    assertEquals("3m", row.age)
  }

  @Test
  fun `a container missing our labels renders dashes rather than blowing up`() {
    val row = toPsRow(container(labels = emptyMap()), now)
    assertEquals("-", row.profile)
    assertEquals("-", row.revision)
  }

  @Test
  fun `age is rendered compactly across scales`() {
    assertEquals("5s", formatAge(now.epochSecond - 5, now))
    assertEquals("4m", formatAge(now.epochSecond - 240, now))
    assertEquals("2h", formatAge(now.epochSecond - 7_200, now))
    assertEquals("3d", formatAge(now.epochSecond - 259_200, now))
  }

  @Test
  fun `an absent or nonsensical creation time renders a dash, not a negative age`() {
    assertEquals("-", formatAge(0, now))
    // Clock skew between the daemon and us shouldn't produce "-5s".
    assertEquals("-", formatAge(now.epochSecond + 60, now))
  }

  @Test
  fun `the table is aligned and carries a header`() {
    val rows =
        listOf(
            toPsRow(container(), now),
            toPsRow(
                container(
                    id = "0000111122223333",
                    state = "exited",
                    status = "Exited (137) 1 minute ago",
                    created = now.epochSecond - 60,
                    labels =
                        mapOf(
                            workloadProfileLabel to "a-much-longer-profile-name",
                            workloadRevisionLabel to "11",
                        ),
                ),
                now,
            ),
        )

    val table = renderPsTable(rows)

    assertEquals(3, table.size, "header + two rows")
    assertTrue(table[0].startsWith("CONTAINER"), table[0])
    assertTrue("PROFILE" in table[0] && "STATE" in table[0] && "AGE" in table[0], table[0])
    // Every column starts at the same offset on every line — that's what makes it scannable.
    val profileColumn = table.map { it.indexOf(it.trim().split(Regex("\\s{2,}"))[1]) }
    assertEquals(1, profileColumn.distinct().size, "profile column should align: $table")
    assertTrue(table.any { "Exited (137) 1 minute ago" in it })
  }

  @Test
  fun `an empty list renders no table at all`() {
    assertEquals(emptyList(), renderPsTable(emptyList()))
  }

  @Test
  fun `running is derived from the daemon's state word`() {
    assertTrue(container(state = "running").running)
    assertTrue(container(state = "Running").running, "the check is case-insensitive")
    assertTrue(!container(state = "exited").running)
    assertTrue(!container(state = "created").running)
  }
}
