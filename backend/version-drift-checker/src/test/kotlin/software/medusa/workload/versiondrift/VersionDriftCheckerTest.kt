package software.medusa.workload.versiondrift

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import software.medusa.workload.server.FleetStore
import software.medusa.workload.server.ImageDigestResolver
import software.medusa.workload.server.ImageResolution
import software.medusa.workload.server.ImageStatus
import software.medusa.workload.server.InMemoryFleetStore
import software.medusa.workload.server.NewProfileRevision
import software.medusa.workload.server.NewRun
import software.medusa.workload.server.NewWorker
import software.medusa.workload.server.ProfileId
import software.medusa.workload.server.RunKind
import software.medusa.workload.server.WorkerId
import software.medusa.workload.server.hashWorkerSecret

private val t0 = Instant.parse("2026-08-04T10:00:00Z")

private class FakeImageDigestResolver(
    private val digest: String?,
    private val status: ImageStatus,
) : ImageDigestResolver {
  override suspend fun resolve(targetServiceAccount: String, imageRef: String) =
      ImageResolution(status, digest)
}

private class RecordingMetricWriter : DriftMetricWriter {
  val writes = mutableListOf<ProfileDriftResult>()

  override fun write(result: ProfileDriftResult, now: Instant) {
    writes += result
  }
}

class VersionDriftCheckerTest {
  private fun test(block: suspend (FleetStore) -> Unit) = runBlocking {
    block(InMemoryFleetStore())
  }

  private suspend fun aWorker(store: FleetStore): WorkerId =
      store
          .createWorker(
              NewWorker(
                  secretHash = hashWorkerSecret("test-worker-secret"),
                  name = "worker-1",
                  hostname = "host.local",
                  os = "linux",
                  cliVersion = "1.0.0",
              )
          )
          .workerId

  /** An image-based profile, its revision pinned to [desiredDigest] and marked RESOLVED. */
  private suspend fun anImageProfile(
      store: FleetStore,
      id: String = "profile-1",
      desiredDigest: String = "sha256:desired",
      dockerImage: String = "us-docker.pkg.dev/proj/repo/image:latest",
  ): ProfileId {
    val profileId = ProfileId(id)
    store.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                targetServiceAccount = "sa@project.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
                dockerImage = dockerImage,
            ),
    )
    store.recordImageDigest(
        profileId,
        revision = 1,
        digest = desiredDigest,
        status = ImageStatus.RESOLVED,
    )
    return profileId
  }

  @Test
  fun `no drift when running and published digests match desired`() = test { store ->
    val profileId = anImageProfile(store)
    val workerId = aWorker(store)
    store.createRun(
        NewRun(
            workerId,
            profileId,
            revision = 1,
            kind = RunKind.RUN,
            imageDigest = "sha256:desired",
        ),
        now = t0,
    )

    val writer = RecordingMetricWriter()
    val checker =
        VersionDriftChecker(
            store,
            FakeImageDigestResolver("sha256:desired", ImageStatus.RESOLVED),
            writer,
        )

    val results = checker.run(now = t0)

    assertEquals(1, results.size)
    assertFalse(results.single().runningMismatch)
    assertFalse(results.single().publishedMismatch)
    assertEquals(1, writer.writes.size)
  }

  @Test
  fun `a live run on an old digest is a running mismatch`() = test { store ->
    val profileId = anImageProfile(store, desiredDigest = "sha256:new")
    val workerId = aWorker(store)
    store.createRun(
        NewRun(workerId, profileId, revision = 1, kind = RunKind.RUN, imageDigest = "sha256:old"),
        now = t0,
    )

    val checker =
        VersionDriftChecker(
            store,
            FakeImageDigestResolver("sha256:new", ImageStatus.RESOLVED),
            RecordingMetricWriter(),
        )

    val result = checker.run(now = t0).single()

    assertTrue(result.runningMismatch)
    assertFalse(result.publishedMismatch)
  }

  @Test
  fun `an ended run does not count as a running mismatch`() = test { store ->
    val profileId = anImageProfile(store, desiredDigest = "sha256:new")
    val workerId = aWorker(store)
    val run =
        store.createRun(
            NewRun(
                workerId,
                profileId,
                revision = 1,
                kind = RunKind.RUN,
                imageDigest = "sha256:old",
            ),
            now = t0,
        )
    store.endRun(run.runId, exitCode = 0, now = t0)

    val checker =
        VersionDriftChecker(
            store,
            FakeImageDigestResolver("sha256:new", ImageStatus.RESOLVED),
            RecordingMetricWriter(),
        )

    assertFalse(checker.run(now = t0).single().runningMismatch)
  }

  @Test
  fun `a published tag that moved past the pinned digest is a published mismatch`() =
      test { store ->
        val profileId = anImageProfile(store, desiredDigest = "sha256:pinned")

        val checker =
            VersionDriftChecker(
                store,
                FakeImageDigestResolver("sha256:published-newer", ImageStatus.RESOLVED),
                RecordingMetricWriter(),
            )

        val result = checker.run(now = t0).single()

        assertFalse(result.runningMismatch)
        assertTrue(result.publishedMismatch)
        assertEquals(profileId.value, result.profileId)
      }

  @Test
  fun `an unresolvable current lookup does not report a published mismatch`() = test { store ->
    anImageProfile(store, desiredDigest = "sha256:pinned")

    val checker =
        VersionDriftChecker(
            store,
            FakeImageDigestResolver(null, ImageStatus.UNDETERMINED),
            RecordingMetricWriter(),
        )

    assertFalse(checker.run(now = t0).single().publishedMismatch)
  }

  @Test
  fun `archived profiles are skipped`() = test { store ->
    val profileId = anImageProfile(store)
    store.archiveProfile(profileId)

    val checker =
        VersionDriftChecker(
            store,
            FakeImageDigestResolver("sha256:desired", ImageStatus.RESOLVED),
            RecordingMetricWriter(),
        )

    assertTrue(checker.run(now = t0).isEmpty())
  }

  @Test
  fun `pure exec profiles with no image are skipped`() = test { store ->
    val profileId = ProfileId("no-image-profile")
    store.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                targetServiceAccount = "sa@project.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
            ),
    )

    val checker =
        VersionDriftChecker(
            store,
            FakeImageDigestResolver("sha256:x", ImageStatus.RESOLVED),
            RecordingMetricWriter(),
        )

    assertTrue(checker.run(now = t0).isEmpty())
  }

  @Test
  fun `a revision whose image never resolved is skipped`() = test { store ->
    val profileId = ProfileId("unresolved-image-profile")
    store.createProfile(
        profileId,
        displayName = null,
        revision =
            NewProfileRevision(
                targetServiceAccount = "sa@project.iam.gserviceaccount.com",
                createdBy = "admin@example.com",
                dockerImage = "us-docker.pkg.dev/proj/repo/image:latest",
            ),
    )
    // Left UNDETERMINED (as initialImageStatus leaves it) — never recorded as RESOLVED.

    val checker =
        VersionDriftChecker(
            store,
            FakeImageDigestResolver("sha256:x", ImageStatus.RESOLVED),
            RecordingMetricWriter(),
        )

    assertTrue(checker.run(now = t0).isEmpty())
  }
}
