package software.medusa.workload.runtime

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorException
import software.medusa.workload.docker.LogStream
import software.medusa.workload.docker.PullProgress
import software.medusa.workload.docker.RegistryAuth

/**
 * The container-run pipeline: claim -> resolve -> brokered image pull -> sidecar wiring ->
 * create/start -> log stream -> wait -> teardown. Extracted from the CLI's `run` command so it can
 * back workloads run on a node, not only via the operator's CLI; every function here is agnostic to
 * how it's driven — no Clikt, no on-disk config, no terminal I/O.
 */
const val workloadProfileLabel = "ms-workload.profile"
const val workloadRevisionLabel = "ms-workload.revision"
const val workloadWorkerLabel = "ms-workload.worker"

// Marks a per-run bridge network as workload-owned, so `workload ps --reap` can sweep any orphaned
// by a hard-killed CLI (M4-B2). `run` doesn't create these today — the metadata emulator runs on
// the host, not a per-run network (see runContainerWithMetadata) — but the label + reap stand ready
// for the future sidecar-container variant that will. Presence == "workload owns it".
const val workloadNetworkLabel = "ms-workload.network"

/** Grace given to the container on Ctrl-C before the daemon SIGKILLs it. */
val containerStopGrace = 10.seconds

/**
 * The repository part of an image ref — the tag (or an existing digest) stripped off.
 * `us-docker.pkg.dev/p/repo/app:v1` -> `us-docker.pkg.dev/p/repo/app`. A `:` is only a tag
 * separator when it sits in the final path segment; before the last `/` it's a registry port.
 */
fun repositoryOf(ref: String): String {
  val trimmed = ref.trim()
  val atIndex = trimmed.indexOf('@')
  val withoutDigest = if (atIndex > 0) trimmed.substring(0, atIndex) else trimmed
  val lastColon = withoutDigest.lastIndexOf(':')
  val lastSlash = withoutDigest.lastIndexOf('/')
  return if (lastColon > lastSlash) withoutDigest.substring(0, lastColon) else withoutDigest
}

/** The registry host of an image ref — the first path segment. */
fun registryHostOf(ref: String): String = ref.trim().substringBefore('/')

/**
 * The username Google's registries expect when the password is an OAuth access token — the same
 * convention `gcloud auth configure-docker`'s helper uses under the hood.
 */
const val brokeredRegistryUsername = "oauth2accesstoken"

/**
 * Whether [host] is a Google-operated container registry. Mirrors the backend's
 * `isGoogleRegistryHost` (which rejects anything else at profile create/update).
 *
 * **A security boundary.** The brokered token is a live credential for the profile's target service
 * account; sending it to a host that isn't Google's would hand that credential away. The backend
 * won't accept a non-Google image ref, so this is belt-and-braces for a profile stored before that
 * rule existed — such an image simply pulls anonymously rather than leaking the token.
 */
fun isGoogleRegistryHost(host: String): Boolean {
  val normalized = host.lowercase()
  return normalized == "gcr.io" || normalized.endsWith(".gcr.io") || normalized.endsWith(".pkg.dev")
}

/**
 * The credentials to pull [pinnedRef] with: the brokered token, but **only** for a Google registry.
 * Null means "pull anonymously" — never "fall back to this host's Docker sign-in".
 *
 * One token, two uses: the same access token authenticates the registry pull and the workload's own
 * GCP access inside the container, so IAM stays coherent — it's one identity end to end.
 */
fun brokeredRegistryAuth(pinnedRef: String, accessToken: String): RegistryAuth? {
  val host = registryHostOf(pinnedRef)
  if (!isGoogleRegistryHost(host)) return null
  return RegistryAuth(
      serverAddress = host,
      username = brokeredRegistryUsername,
      password = accessToken,
  )
}

/**
 * The immutable ref to actually pull and run: the repository addressed by the digest the revision
 * pinned at creation time, so a tag that has since moved can't change what runs here.
 */
fun pinnedImageRef(image: ClaimImage): String = "${repositoryOf(image.ref)}@${image.digest}"

/**
 * The container's environment for the default (Beacon) path: the profile's plain vars + resolved
 * secret values + the non-secret metadata-emulator **pointer** vars. The brokered token is
 * deliberately **absent** — the workload fetches (and refreshes) it from the emulator at
 * [pointerEnv]'s address, so `docker inspect` shows a pointer, not a credential. Deliberately does
 * **not** inherit this host's environment.
 *
 * Returned as `KEY=VALUE` strings for the create body only; nothing here ever reaches a command
 * line, so values can't show up in `ps` on the host.
 */
fun buildMetadataContainerEnv(
    profileEnv: Map<String, String>,
    pointerEnv: Map<String, String>,
): List<String> = (profileEnv + pointerEnv).map { (name, value) -> "$name=$value" }

/**
 * Whether a failed `docker pull`'s output reads like a registry auth problem, as opposed to (say) a
 * missing tag or a network error. Keyword-matched because the CLI's exact wording varies by version
 * and registry; a false positive only costs an extra hint line.
 */
fun looksLikeAuthFailure(output: String): Boolean {
  val text = output.lowercase()
  return listOf(
          "unauthorized",
          "authentication required",
          "denied",
          "forbidden",
          "no basic auth credentials",
          "login",
      )
      .any { it in text }
}

/**
 * The message for a failed pull. An auth failure now means the *profile's* target service account
 * can't read the repository — nothing about this host's own sign-in, which `workload worker run` no
 * longer uses. Deliberately does **not** suggest `gcloud auth configure-docker`: falling back to
 * ambient developer credentials would mask a broken opt-in grant and make the profile look fine on
 * the one machine that happens to be logged in.
 */
fun pullFailureMessage(pinnedRef: String, serviceAccount: String, reason: String): String {
  val base = "Failed to pull $pinnedRef: $reason"
  if (!looksLikeAuthFailure(reason)) {
    return base
  }
  return "$base\n" +
      "The pull authenticated as the profile's target service account ($serviceAccount), which\n" +
      "appears to lack read access to this repository. An admin needs to grant it\n" +
      "roles/artifactregistry.reader — via the workload-impersonation module's\n" +
      "artifact_repository_id input — and then re-verify the profile.\n" +
      "(`workload worker run` deliberately does not fall back to this machine's own Docker login.)"
}

/**
 * Turns a failed claim into an actionable message. Shared by every command that claims a profile
 * (`token`, `run`, `exec`): a 404 is deliberately ambiguous on the server side (the worker plane is
 * unprobeable), so the message spells out both things it can mean.
 */
fun tokenClaimErrorMessage(e: WorkerApiException, profileId: String): String =
    if (e.statusCode == 404) {
      // The v2 worker plane returns a bare 404 for a wrong URL *and* for any rejected credential
      // (revoked, or a worker that never activated) — it's deliberately indistinguishable.
      "The broker returned 404. That means either a wrong broker URL, or a credential the broker " +
          "rejected — this worker may have been revoked or never activated. Check " +
          "'workload worker status', and re-register with 'workload worker register --force' if needed."
    } else
        when (e.errorCode) {
          "unauthorized" ->
              "Worker is not approved (pending, rejected, or revoked). Run 'workload worker status' to check."
          "profile_not_found" ->
              "No such profile '$profileId'. Check the profile ID, or ask an admin to grant it to you."
          "profile_archived" ->
              "Profile '$profileId' has been archived and can no longer be claimed."
          "not_granted" ->
              "You don't have access to profile '$profileId'. Ask an admin to grant it to you."
          "failed_to_mint_token" ->
              "The broker failed to mint a token — likely an IAM misconfiguration on the target service account. Contact an admin."
          "not_verified" ->
              "Profile '$profileId' has an unverified revision and can't be claimed. Ask an admin to re-verify it."
          "image_unresolvable" ->
              "Profile '$profileId' has an image whose digest couldn't be resolved, so it can't be claimed. " +
                  "Its target service account likely lacks roles/artifactregistry.reader on the image's repository " +
                  "(see the workload-impersonation module's artifact_repository_id input). Ask an admin to re-verify it."
          else -> "Token claim failed: ${e.errorCode}"
        }

/**
 * Renders one progress record as a line, or null to skip it. The daemon emits a record per layer
 * per byte-range; keying on (id, status) collapses that to one line per state change, which reads
 * well both on a terminal and in a log. [seen] carries the dedupe state across a pull.
 */
fun renderPullProgress(progress: PullProgress, seen: MutableSet<String>): String? {
  val status = progress.status?.takeIf { it.isNotBlank() } ?: return null
  val key = "${progress.id.orEmpty()}|$status"
  if (!seen.add(key)) return null
  return if (progress.id.isNullOrBlank()) status else "${progress.id}: $status"
}

/**
 * Pulls [pinnedRef], authenticating with the brokered [accessToken] only when the registry is
 * Google's (see [brokeredRegistryAuth]). [onProgress] is called once per rendered progress line
 * (see [renderPullProgress]); throws [DockerConnectorException] on failure, covering both an HTTP
 * status (`DockerApiException`) and an in-stream error record the daemon may send after a 200
 * (`DockerPullException`).
 */
suspend fun pullBrokeredImage(
    connector: DockerConnector,
    pinnedRef: String,
    accessToken: String,
    onProgress: (String) -> Unit = {},
) {
  val seen = mutableSetOf<String>()
  val auth = brokeredRegistryAuth(pinnedRef, accessToken)
  connector.images.pull(pinnedRef, auth).collect { progress ->
    renderPullProgress(progress, seen)?.let(onProgress)
  }
}

/** The container labels `workload run` stamps every container it creates with. */
fun containerLabels(claim: WorkerClaimResponse, workerId: String): Map<String, String> =
    mapOf(
        workloadProfileLabel to claim.profileId,
        workloadRevisionLabel to claim.revision.toString(),
        workloadWorkerLabel to workerId,
    )

/**
 * create -> start -> stream logs -> wait, returning the container's exit code. The whole post-pull
 * lifecycle goes through the library (no `docker` CLI), which is what stage 1 of the migration
 * ladder is proving out.
 *
 * [onCreated] fires with the container id as soon as it exists, so a caller can arm teardown before
 * the container is started. [cmd] overrides the image's own command — `workload worker run` leaves
 * it null (the profile's image decides what to run); tests use it to drive a stock image.
 *
 * **Why not AutoRemove.** The obvious shape is `AutoRemove=true` and let the daemon reap the
 * container. It doesn't work here: a short-lived container (`echo` and exit) is reaped before our
 * follow-logs request lands, and the daemon answers `404 No such container` — the run's entire
 * output is lost. Docker's own CLI dodges this by attaching *before* it starts the container; our
 * log stream is a cold Flow whose request is only issued once collection begins, so we can't
 * guarantee that ordering without new connector API. Creating without AutoRemove and removing
 * explicitly in a `finally` is deterministic and leaves nothing behind — at the cost of a lingering
 * container if this process is SIGKILLed, which is inside the accepted teardown boundary.
 */
suspend fun runContainerToCompletion(
    connector: DockerConnector,
    image: String,
    env: List<String>,
    labels: Map<String, String>,
    onCreated: suspend (String) -> Unit = {},
    cmd: List<String>? = null,
    extraHosts: List<String> = emptyList(),
    networkMode: String? = null,
    onStdout: (ByteArray) -> Unit,
    onStderr: (ByteArray) -> Unit,
): Int {
  val created =
      connector.containers.create(
          image = image,
          cmd = cmd,
          env = env,
          labels = labels,
          autoRemove = false,
          extraHosts = extraHosts,
          networkMode = networkMode,
      )
  onCreated(created.id)

  try {
    connector.containers.start(created.id)
    return coroutineScope {
      val exit = async { connector.containers.wait(created.id) }

      // Log streaming is best-effort: the container's lifecycle is governed by `wait` (its exit
      // code), not by the log follow. If the follow stream drops mid-run, warn and keep waiting —
      // a broken log tail must never tear down an otherwise-healthy long-running workload.
      try {
        connector.logs.logs(created.id, follow = true).collect { frame ->
          when (frame.stream) {
            LogStream.STDOUT -> onStdout(frame.bytes)
            LogStream.STDERR -> onStderr(frame.bytes)
          }
        }
      } catch (e: DockerConnectorException) {
        System.err.write(
            "workload: log streaming interrupted (${e.message}); the container keeps running.\n"
                .toByteArray()
        )
        System.err.flush()
      }
      exit.await().statusCode
    }
  } finally {
    // NonCancellable so the container is still reaped when the collector is cancelled.
    withContext(NonCancellable) {
      runCatching { connector.containers.remove(created.id, force = true) }
    }
  }
}

/**
 * Best-effort teardown on Ctrl-C/SIGTERM: stop the container (SIGTERM, then SIGKILL after [grace])
 * and remove it, then run [onShutdown] (e.g. closing a sidecar) regardless of whether the stop/
 * remove succeeded. The JVM exits through this hook rather than unwinding, so a caller's own
 * `finally` may never run — this is what actually cleans up on Ctrl-C. Accepted teardown boundary:
 * a `kill -9` of this JVM can still leave a container behind, which `workload ps --reap` clears.
 */
fun installContainerTeardownHook(
    connector: DockerConnector,
    containerId: String,
    grace: Duration,
    onShutdown: () -> Unit = {},
): Thread {
  val hook = Thread {
    runCatching {
      runBlocking {
        connector.containers.stop(containerId, grace)
        connector.containers.remove(containerId, force = true)
      }
    }
    onShutdown()
  }
  Runtime.getRuntime().addShutdownHook(hook)
  return hook
}
