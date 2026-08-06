package software.medusa.workload.agent

import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import software.medusa.workload.docker.DockerConnector
import software.medusa.workload.docker.DockerConnectorConfig
import software.medusa.workload.runtime.DesiredAssignment
import software.medusa.workload.runtime.RunReporter
import software.medusa.workload.runtime.SecretBrokerAuth
import software.medusa.workload.runtime.claimWorkload
import software.medusa.workload.runtime.collectUnderDiskPressure
import software.medusa.workload.runtime.diffAssignments
import software.medusa.workload.runtime.fetchAssignments
import software.medusa.workload.runtime.resolveSecrets
import software.medusa.workload.runtime.startAssignment
import software.medusa.workload.runtime.stopSuperseded
import software.medusa.workload.runtime.workloadAgentManagedLabel

private val reconcileInterval = 15.seconds
private val diskPressureCheckInterval = 5.minutes()

private fun Int.minutes() = (this * 60).seconds

/**
 * The always-on node daemon (M7-05). Reconciles this node's containers against its assignment set
 * every [reconcileInterval], reports its own presence as an AGENT-kind run for as long as it's up,
 * honors the operator pause/serve switch (a paused node reconciles to an empty desired set — a
 * drain, not a container hard-kill mid-tick), and runs disk-pressure GC on a slower timer. See
 * `libraries/workload-runtime`'s `AgentReconciler.kt`/`DiskPressureGc.kt` for the reused,
 * independently-tested logic; this file is only wiring + the loop.
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
              val assignments = fetchAssignments(brokerUrl, auth)
              val desired =
                  if (assignments.paused) emptyList()
                  else
                      assignments.assignments.map {
                        DesiredAssignment(it.profileId, it.revision, it.dockerImageDigest)
                      }
              val running = connector.containers.list(labelKeys = listOf(workloadAgentManagedLabel))
              val plan = diffAssignments(desired, running)

              stopSuperseded(connector, plan, ::warn)
              for (assignment in plan.toStart) {
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
