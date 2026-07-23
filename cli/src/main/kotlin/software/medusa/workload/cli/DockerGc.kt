package software.medusa.workload.cli

import java.nio.file.Path
import software.medusa.workload.docker.ContainerSummary
import software.medusa.workload.docker.DockerApiException
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorException
import software.medusa.workload.docker.ImageSummary

/**
 * Docker garbage collection (M7-00) — the seam that keeps a long-lived node's disk from filling
 * with superseded, digest-pinned revisions. Lives here, at the current CLI run path; the M7-01
 * runtime extraction moves it into `workload-runtime` unchanged, and M7-05's agent later widens the
 * image rule into disk-pressure watermarks over the assignment set.
 *
 * Two operations, both **best-effort** (a GC failure is a logged warning, never a run failure) and
 * both scoped by *ownership*, never by "unused":
 * - **Container reap** — `containers.prune` filtered to `ms-workload.profile`, so only our own
 *   exited containers go; a running container (ours or the user's) is never touched by prune.
 * - **Image sweep** — for a repository workload has run, delete the local images of *that repo*
 *   that are neither wanted (the digest just pulled) nor in use by a live container. Never `force`:
 *   the daemon's refusal to delete an in-use image is the "keep it" signal, not something to
 *   override.
 */

/**
 * The image **references** under [repository] that are superseded and safe to delete: the
 * `repo@digest` / `repo:tag` handles of [images] belonging to the repo, whose owning image is
 * neither wanted (a [keepDigests] digest — e.g. the just-pulled one) nor referenced by anything in
 * [inUse] (image ids / refs of live containers). Unrelated repositories never match, so this stays
 * safe on a shared daemon (a dev Mac) where a blanket image prune would be hostile.
 *
 * **References, not image ids, deliberately.** Deleting by id fails (`409`) when the image also
 * carries another repo's tag; untagging just this repo's reference reclaims exactly what workload
 * owns and lets the daemon delete the image only once its last reference is gone — the shared-base
 * case degrades to "kept", never to a forced delete. References are distinct.
 */
internal fun collectableImageRefs(
    images: List<ImageSummary>,
    repository: String,
    keepDigests: Set<String>,
    inUse: Set<String>,
): List<String> =
    images
        .flatMap { image ->
          val repoDigests = image.repoDigests ?: emptyList()
          val repoTags = image.repoTags ?: emptyList()
          val digestRefs = repoDigests.filter { it.substringBeforeLast('@') == repository }
          val tagRefs = repoTags.filter { repositoryOf(it) == repository }
          val ownRefs = digestRefs + tagRefs
          if (ownRefs.isEmpty()) return@flatMap emptyList()

          val wanted = digestRefs.any { digestOf(it) in keepDigests }
          // A live container may name the image by any of its refs, or by id — check them all, not
          // just this repo's, so an image shared into another repo isn't torn out from under a run.
          val used =
              image.id in inUse || repoDigests.any { it in inUse } || repoTags.any { it in inUse }
          if (wanted || used) emptyList() else ownRefs
        }
        .distinct()

/** The digest half of a `repo@sha256:...` reference — `sha256:...`. */
private fun digestOf(repoDigest: String): String = repoDigest.substringAfterLast('@')

/**
 * The image references currently pinned by live (running) workload containers on this daemon —
 * their `Image` field, which is either an image id (`sha256:...`) or a `repo@digest`. Used only to
 * avoid noisy "kept, in use" warnings when sweeping; the real in-use guard is never-force deletion.
 * Empty on any listing error (the sweep then relies on that guard alone).
 */
internal suspend fun imageRefsInUse(connector: DockerConnector): Set<String> =
    runCatching {
          connector.containers
              .list(labelKeys = listOf(workloadProfileLabel), all = false)
              .mapNotNull(ContainerSummary::image)
              .toSet()
        }
        .getOrDefault(emptySet())

/**
 * Sweeps one repository: deletes its collectable images (never-force), returning how many the
 * daemon actually removed. A `409`-style refusal (image still in use / referenced under several
 * repos) is the expected "keep" outcome and is reported through [onKept] rather than as an error;
 * any other failure goes to [warn]. A failure to even list images is swallowed to [warn] and
 * returns 0.
 */
internal suspend fun sweepRepository(
    connector: DockerConnector,
    repository: String,
    keepDigests: Set<String>,
    inUse: Set<String>,
    warn: (String) -> Unit,
    onKept: (String) -> Unit = {},
): Int {
  val images =
      runCatching { connector.images.list() }
          .getOrElse {
            warn("workload: image GC skipped for $repository (${it.reason()})")
            return 0
          }
  var deleted = 0
  for (ref in collectableImageRefs(images, repository, keepDigests, inUse)) {
    runCatching { connector.images.remove(ref, force = false) }
        .fold(
            onSuccess = { deleted++ },
            onFailure = { e ->
              if (e is DockerApiException && e.statusCode == httpConflict) {
                onKept("kept $ref — still in use")
              } else {
                warn("workload: could not remove image $ref: ${e.reason()}")
              }
            },
        )
  }
  return deleted
}

/**
 * Post-run GC (M7-00): record [repository] as workload-managed for later `workload worker prune`,
 * reap our own exited containers, then sweep the repo down to [keepDigest] (the digest of the
 * revision just run). Entirely best-effort; every failure is a [warn], never propagated. Called
 * right after a successful pull and again at teardown — the moments the old digest is known
 * superseded.
 */
internal suspend fun garbageCollectAfterRun(
    connector: DockerConnector,
    configDir: Path,
    repository: String,
    keepDigest: String,
    warn: (String) -> Unit,
) {
  recordManagedRepo(configDir, repository)
  runCatching { connector.containers.prune(labelKeys = listOf(workloadProfileLabel)) }
      .onFailure { warn("workload: could not reap exited containers (${it.reason()})") }
  sweepRepository(
      connector = connector,
      repository = repository,
      keepDigests = setOf(keepDigest),
      inUse = imageRefsInUse(connector),
      warn = warn,
  )
}

private const val httpConflict = 409

/** The connector's actionable message, or the throwable's own text if it isn't a connector one. */
internal fun Throwable.reason(): String =
    (this as? DockerConnectorException)?.message ?: message ?: toString()
