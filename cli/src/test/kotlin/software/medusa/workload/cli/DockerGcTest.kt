package software.medusa.workload.cli

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import software.medusa.workload.docker.ImageSummary

/**
 * Unit coverage for the ownership-scoped GC policy (M7-00) — the rule that decides *which* local
 * images a run supersedes. The real Docker round-trip is in `DockerGcContractTest`; here we pin the
 * decision itself, which is the heart of "two bumps leave exactly one image, unrelated images
 * untouched".
 */
class DockerGcTest {

  private val repo = "us-docker.pkg.dev/p/repo/app"
  private val other = "us-docker.pkg.dev/p/repo/sidecar"

  private fun image(
      id: String,
      repoDigests: List<String>? = null,
      repoTags: List<String>? = null,
  ) = ImageSummary(id = id, repoDigests = repoDigests, repoTags = repoTags)

  @Test
  fun `a superseded revision of the repo is collectable, the just-pulled one is kept`() {
    val old = image("sha256:old", repoDigests = listOf("$repo@sha256:AAA"))
    val new = image("sha256:new", repoDigests = listOf("$repo@sha256:BBB"))

    val collectable =
        collectableImageRefs(
            images = listOf(old, new),
            repository = repo,
            keepDigests = setOf("sha256:BBB"),
            inUse = emptySet(),
        )

    assertEquals(
        listOf("$repo@sha256:AAA"),
        collectable,
        "only the old digest's ref should be collectable",
    )
  }

  @Test
  fun `an unrelated repository is never collectable`() {
    val mine = image("sha256:old", repoDigests = listOf("$repo@sha256:AAA"))
    val theirs = image("sha256:ext", repoDigests = listOf("$other@sha256:ZZZ"))
    val hub = image("sha256:hub", repoTags = listOf("busybox:latest"))

    val collectable =
        collectableImageRefs(
            images = listOf(mine, theirs, hub),
            repository = repo,
            keepDigests = setOf("sha256:BBB"), // the current digest isn't even present locally yet
            inUse = emptySet(),
        )

    assertEquals(
        listOf("$repo@sha256:AAA"),
        collectable,
        "only this repo's superseded ref is swept",
    )
  }

  @Test
  fun `an image still referenced by a live container is kept even when superseded`() {
    val old = image("sha256:old", repoDigests = listOf("$repo@sha256:AAA"))
    val superseded = image("sha256:mid", repoDigests = listOf("$repo@sha256:BBB"))

    // A concurrent run of the mid revision holds it (by id); GC must not list it for deletion.
    val collectable =
        collectableImageRefs(
            images = listOf(old, superseded),
            repository = repo,
            keepDigests = setOf("sha256:CCC"),
            inUse = setOf("sha256:mid"),
        )

    assertEquals(
        listOf("$repo@sha256:AAA"),
        collectable,
        "in-use image kept, only the truly-old ref goes",
    )
  }

  @Test
  fun `in-use is honored whether the container names the image by id or by repo digest`() {
    val byRef = image("sha256:mid", repoDigests = listOf("$repo@sha256:BBB"))
    val collectable =
        collectableImageRefs(
            images = listOf(byRef),
            repository = repo,
            keepDigests = emptySet(),
            inUse = setOf("$repo@sha256:BBB"),
        )
    assertTrue(collectable.isEmpty(), "a container naming the image by repo@digest keeps it too")
  }

  @Test
  fun `repo membership is recognized from a repo tag, not only a digest`() {
    val tagged = image("sha256:tagged", repoTags = listOf("$repo:v1"))
    val collectable =
        collectableImageRefs(
            images = listOf(tagged),
            repository = repo,
            keepDigests = emptySet(),
            inUse = emptySet(),
        )
    assertEquals(listOf("$repo:v1"), collectable, "a repo-tagged image's tag ref is collectable")
  }

  @Test
  fun `a dangling image belongs to no repo and is never swept`() {
    val dangling = image("sha256:dangling", repoDigests = null, repoTags = null)
    val collectable =
        collectableImageRefs(
            images = listOf(dangling),
            repository = repo,
            keepDigests = emptySet(),
            inUse = emptySet(),
        )
    assertTrue(collectable.isEmpty(), "ownership GC never touches dangling images")
  }

  @Test
  fun `only this repo's own references are collected from a multi-repo image`() {
    // Same image shared into our repo and another; only our reference should be untagged.
    val multi =
        image(
            "sha256:multi",
            repoDigests = listOf("$repo@sha256:AAA", "$other@sha256:AAA"),
            repoTags = listOf("$repo:old"),
        )
    val collectable =
        collectableImageRefs(
            images = listOf(multi),
            repository = repo,
            keepDigests = emptySet(),
            inUse = emptySet(),
        )
    assertEquals(
        setOf("$repo@sha256:AAA", "$repo:old"),
        collectable.toSet(),
        "only the target repo's refs are collected — never the other repo's",
    )
  }

  // --- Managed-repo persistence (GcState) ---

  @Test
  fun `recording a repo makes it readable, is idempotent, and starts empty`() {
    val dir = Files.createTempDirectory("gc-state")
    assertTrue(loadManagedRepos(dir).isEmpty(), "no file yet -> empty set")

    recordManagedRepo(dir, repo)
    recordManagedRepo(dir, repo) // idempotent
    recordManagedRepo(dir, other)

    assertEquals(setOf(repo, other), loadManagedRepos(dir))
  }

  @Test
  fun `an absent config dir is created by recording, and a corrupt file reads as empty`() {
    val parent = Files.createTempDirectory("gc-state-parent")
    val dir = parent.resolve("prod") // does not exist yet
    assertFalse(Files.exists(dir))

    recordManagedRepo(dir, repo)
    assertEquals(setOf(repo), loadManagedRepos(dir))

    Files.writeString(managedReposFile(dir), "{ not json")
    assertTrue(loadManagedRepos(dir).isEmpty(), "unreadable state must not throw, just read empty")
  }

  // --- df summary formatting ---

  @Test
  fun `formatBytes renders human units`() {
    assertEquals("512 B", formatBytes(512))
    assertEquals("1.0 KB", formatBytes(1024))
    assertEquals("1.5 KB", formatBytes(1536))
    assertEquals("2.0 MB", formatBytes(2L * 1024 * 1024))
    assertEquals("3.0 GB", formatBytes(3L * 1024 * 1024 * 1024))
  }
}
