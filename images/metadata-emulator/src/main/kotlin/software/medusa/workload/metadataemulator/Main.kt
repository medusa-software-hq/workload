package software.medusa.workload.metadataemulator

import java.util.concurrent.CountDownLatch
import org.slf4j.LoggerFactory
import software.medusa.workload.runtime.SidecarConfigException
import software.medusa.workload.runtime.metadataEmulatorFromEnv

private val log = LoggerFactory.getLogger("software.medusa.workload.metadataemulator.Main")

/**
 * The metadata emulator packaged as its own container, run by `workload run` as a per-run sidecar
 * (see `libraries/workload-runtime`'s `MetadataSidecar.kt` for the isolation design this exists
 * for). Everything here already lives in the runtime library — `metadataEmulatorFromEnv` reads this
 * process's env (`MS_SIDECAR_*`, written by the run pipeline) and builds the same
 * [MetadataEmulator] class `workload exec` runs in-process — so this file is just: build it, start
 * it, stay up until `docker stop` sends SIGTERM.
 */
fun main() {
  val emulator =
      try {
        metadataEmulatorFromEnv()
      } catch (e: SidecarConfigException) {
        log.error("metadata-emulator: {}", e.message)
        kotlin.system.exitProcess(1)
      }

  emulator.start()
  log.info("event=sidecar.start listening={}", emulator.hostPort)

  val stopped = CountDownLatch(1)
  Runtime.getRuntime()
      .addShutdownHook(
          Thread {
            log.info("event=sidecar.stop")
            runCatching { emulator.close() }
            stopped.countDown()
          }
      )
  stopped.await()
}
