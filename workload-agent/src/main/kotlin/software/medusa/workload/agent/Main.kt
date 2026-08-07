package software.medusa.workload.agent

import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import software.medusa.workload.docker.ContainerSummary
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig
import software.medusa.workload.runtime.AssignmentStatusReport
import software.medusa.workload.runtime.BrokerAuth
import software.medusa.workload.runtime.DesiredAssignment
import software.medusa.workload.runtime.RunReporter
import software.medusa.workload.runtime.SecretBrokerAuth
import software.medusa.workload.runtime.beginDrains
import software.medusa.workload.runtime.claimWorkload
import software.medusa.workload.runtime.collectUnderDiskPressure
import software.medusa.workload.runtime.crashloopThreshold
import software.medusa.workload.runtime.exitedAbnormally
import software.medusa.workload.runtime.fetchAssignments
import software.medusa.workload.runtime.forceStopExpiredDrains
import software.medusa.workload.runtime.loadCrashloopRecords
import software.medusa.workload.runtime.parseDrainDeadline
import software.medusa.workload.runtime.planReconcile
import software.medusa.workload.runtime.pruneCrashloopRecords
import software.medusa.workload.runtime.recordCrashloopFailure
import software.medusa.workload.runtime.removeDeadContainers
import software.medusa.workload.runtime.reportAssignmentStatuses
import software.medusa.workload.runtime.resolveSecrets
import software.medusa.workload.runtime.saveCrashloopRecords
import software.medusa.workload.runtime.startAssignment
import software.medusa.workload.runtime.summarizeAssignmentStatuses
import software.medusa.workload.runtime.workloadAgentManagedLabel
import software.medusa.workload.runtime.workloadImageDigestLabel
import software.medusa.workload.runtime.workloadProfileLabel

private val reconcileInterval = 15.seconds
private val diskPressureCheckInterval = 5.minutes()

private fun Int.minutes() = (this * 60).seconds

/**
 * The always-on node daemon (M7-05, amended for the M7 automated-rollout drain contract).
 * Reconciles this node's containers against its assignment set every [reconcileInterval], reports
 * its own presence as an AGENT-kind run for as long as it's up, honors the operator pause/serve
 * switch (a paused node reconciles to an empty desired set — a drain, same contract as any other
 * supersession, not a container hard-kill mid-tick), holds instead of restarting a crashlooping
 * assignment, and runs disk-pressure GC on a slower timer. See `libraries/workload-runtime`'s
 * `AgentReconciler.kt`/`CrashloopState.kt`/`DiskPressureGc.kt` for the reused, independently-tested
 * logic; this file is only wiring + the loop.
 */
fun main() {
  val environment = System.getenv("WORKLOAD_ENVIRONMENT")
  val brokerUrl = brokerBaseUrl(environment)
  val dir = configDir(environment)
  val config =
      loadWorkloadConfig(dir)
          ?: run {
            System.err.println(
                "workload-agent: no worker credential at $dir/config.json — this node hasn't " +
                    "completed 'workload worker register' yet. Exiting."
            )
            kotlin.system.exitProcess(1)
          }
  val auth = SecretBrokerAuth(config.workerId, config.workerSecret)
  val dockerRoot = Path.of(System.getenv("WORKLOAD_DOCKER_ROOT") ?: "/var/lib/docker")

  fun warn(message: String) = System.err.println(message)

  DockerConnector(DockerConnectorConfig.fromEnvironment()).use { connector ->
    val reporter =
        RunReporter.start(
            brokerBaseUrl = brokerUrl,
            auth = auth,
            profileId = null,
            revision = null,
            kind = "agent",
            imageDigest = null,
            warn = ::warn,
        )
    Runtime.getRuntime().addShutdownHook(Thread { runCatching { reporter?.close() } })

    var sinceDiskCheck = diskPressureCheckInterval
    runBlocking {
      while (true) {
        runCatching {
              val now = Instant.now()
              val assignments = fetchAssignments(brokerUrl, auth)
              val desired =
                  if (assignments.paused) emptyList()
                  else
                      assignments.assignments.map {
                        DesiredAssignment(
                            profileId = it.profileId,
                            revision = it.revision,
                            dockerImageDigest = it.dockerImageDigest,
                            drainDeadline = parseDrainDeadline(it.drainDeadline),
                        )
                      }
              val running = connector.containers.list(labelKeys = listOf(workloadAgentManagedLabel))
              val plan = planReconcile(desired, running, now)

              // Crashloop bookkeeping: fold this tick's abnormal exits of the *current* desired
              // digest into the local record before deciding what to (re)start, then drop history
              // for anything no longer desired (or bumped to a new digest) so the file can't grow
              // forever and a fixed digest bump always clears an old hold.
              var crashloopRecords = loadCrashloopRecords(dir)
              for (container in plan.crashloopCandidates) {
                if (!container.exitedAbnormally()) continue
                val profileId = container.labels[workloadProfileLabel] ?: continue
                val digest = container.labels[workloadImageDigestLabel] ?: continue
                crashloopRecords =
                    recordCrashloopFailure(crashloopRecords, profileId, digest, now.epochSecond)
              }
              val desiredDigestsByProfile = desired.associate {
                it.profileId to it.dockerImageDigest
              }
              crashloopRecords = pruneCrashloopRecords(crashloopRecords, desiredDigestsByProfile)
              saveCrashloopRecords(dir, crashloopRecords)

              val heldSince =
                  crashloopRecords
                      .filter { it.holding }
                      .associate { record ->
                        record.profileId to Instant.ofEpochSecond(record.failureEpochSeconds.min())
                      }
              for (profileId in heldSince.keys) {
                if (desired.any { it.profileId == profileId }) {
                  warn(
                      "workload-agent: '$profileId' has crashed more than $crashloopThreshold " +
                          "times in its crashloop window — holding, not restarting. An operator " +
                          "must intervene (see 'admin workers list')."
                  )
                }
              }
              val toStart = plan.toStart.filterNot { it.profileId in heldSince }

              removeDeadContainers(connector, plan.toRemove, ::warn)
              forceStopExpiredDrains(connector, plan.toForceStop, ::warn)
              beginDrains(connector, brokerUrl, auth, plan.toBeginDrain, now, ::warn)

              for (assignment in toStart) {
                runCatching {
                      val claim = claimWorkload(brokerUrl, auth, assignment.profileId)
                      val secrets = resolveSecrets(claim.secretEnvVars, claim.accessToken)
                      startAssignment(
                          connector,
                          config.workerId,
                          assignment,
                          claim,
                          secrets,
                          ::warn,
                      )
                    }
                    .onFailure {
                      warn(
                          "workload-agent: claim failed for '${assignment.profileId}': ${it.message}"
                      )
                    }
              }

              reportStatus(brokerUrl, auth, desired, running, now, heldSince, ::warn)
            }
            .onFailure { warn("workload-agent: reconcile pass failed: ${it.message}") }

        if (sinceDiskCheck >= diskPressureCheckInterval) {
          runCatching { collectUnderDiskPressure(connector, dir, dockerRoot, warn = ::warn) }
              .onFailure { warn("workload-agent: disk-pressure check failed: ${it.message}") }
          sinceDiskCheck = 0.seconds
        }

        delay(reconcileInterval)
        sinceDiskCheck += reconcileInterval
      }
    }
  }
}

/**
 * Reports this tick's per-profile status for `admin workers list` — best-effort: a failure here
 * only costs observability, never the reconcile pass itself, so it's swallowed with a warning
 * rather than propagated.
 */
private fun reportStatus(
    brokerUrl: String,
    auth: BrokerAuth,
    desired: List<DesiredAssignment>,
    running: List<ContainerSummary>,
    now: Instant,
    heldSince: Map<String, Instant>,
    warn: (String) -> Unit,
) {
  runCatching {
        val statuses = summarizeAssignmentStatuses(desired, running, now, heldSince)
        reportAssignmentStatuses(
            brokerUrl,
            auth,
            statuses.map {
              AssignmentStatusReport(
                  profileId = it.profileId,
                  runningDigest = it.runningDigest,
                  desiredDigest = it.desiredDigest,
                  state = it.state.name.lowercase(),
                  since = it.since.toString(),
                  drainDeadline = it.drainDeadline?.toString(),
              )
            },
        )
      }
      .onFailure { warn("workload-agent: status report failed: ${it.message}") }
}
