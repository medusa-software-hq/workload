package software.medusa.workload.docker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Result of `GET /_ping`. Docker's ping body is just `OK`; the useful bits are response headers,
 * which the connector lifts into this type.
 */
data class PingResult(
    val apiVersion: String,
    val builderVersion: String?,
    val experimental: Boolean,
)

/** Subset of `GET /version` we rely on. Field names/shape mirror docker-py's `/version`. */
@Serializable
data class VersionInfo(
    @SerialName("Version") val version: String,
    @SerialName("ApiVersion") val apiVersion: String,
    @SerialName("MinAPIVersion") val minApiVersion: String? = null,
    @SerialName("GitCommit") val gitCommit: String? = null,
    @SerialName("GoVersion") val goVersion: String? = null,
    @SerialName("Os") val os: String? = null,
    @SerialName("Arch") val arch: String? = null,
    @SerialName("KernelVersion") val kernelVersion: String? = null,
    @SerialName("Experimental") val experimental: Boolean = false,
)

/** Subset of `GET /info` we rely on. There are dozens more fields; we deserialize leniently. */
@Serializable
data class SystemInfo(
    @SerialName("ID") val id: String? = null,
    @SerialName("Containers") val containers: Int = 0,
    @SerialName("ContainersRunning") val containersRunning: Int = 0,
    @SerialName("ContainersPaused") val containersPaused: Int = 0,
    @SerialName("ContainersStopped") val containersStopped: Int = 0,
    @SerialName("Images") val images: Int = 0,
    @SerialName("ServerVersion") val serverVersion: String? = null,
    @SerialName("Driver") val driver: String? = null,
    @SerialName("OperatingSystem") val operatingSystem: String? = null,
    @SerialName("OSType") val osType: String? = null,
    @SerialName("Architecture") val architecture: String? = null,
    @SerialName("NCPU") val ncpu: Int = 0,
    @SerialName("MemTotal") val memTotal: Long = 0,
)

/** The shape of a Docker daemon error body: `{"message": "..."}`. */
@Serializable internal data class DaemonErrorBody(val message: String? = null)

// --- Containers (M3-02) ---

/**
 * `HostConfig` subset we set on create. [autoRemove] maps to `--rm`: the daemon reaps the
 * container's filesystem the moment it exits, so short-lived workload runs leave nothing behind.
 */
@Serializable
internal data class HostConfig(
    @SerialName("AutoRemove") val autoRemove: Boolean,
)

/**
 * Request body for `POST /containers/create`. Env is passed **only here**, in the JSON body — never
 * on a command line — so secrets in the environment never reach `argv` or a process listing.
 */
@Serializable
internal data class ContainerCreateRequest(
    @SerialName("Image") val image: String,
    @SerialName("Cmd") val cmd: List<String>? = null,
    @SerialName("Env") val env: List<String>,
    @SerialName("Labels") val labels: Map<String, String>,
    @SerialName("Tty") val tty: Boolean,
    @SerialName("HostConfig") val hostConfig: HostConfig,
)

/** Response of `POST /containers/create`. */
@Serializable
data class ContainerCreateResponse(
    @SerialName("Id") val id: String,
    @SerialName("Warnings") val warnings: List<String> = emptyList(),
)

/** Response of `POST /containers/{id}/wait`: the container's exit code, plus any wait error. */
@Serializable
data class ContainerWaitResult(
    @SerialName("StatusCode") val statusCode: Int,
    @SerialName("Error") val error: WaitError? = null,
) {
  @Serializable data class WaitError(@SerialName("Message") val message: String? = null)
}

/** One entry from `GET /containers/json` — the minimal projection we consume. */
@Serializable
data class ContainerSummary(
    @SerialName("Id") val id: String,
    @SerialName("Names") val names: List<String> = emptyList(),
    @SerialName("Image") val image: String? = null,
    @SerialName("State") val state: String? = null,
    @SerialName("Status") val status: String? = null,
    @SerialName("Labels") val labels: Map<String, String> = emptyMap(),
)

/** Minimal typed projection of `GET /containers/{id}/json`. */
@Serializable
data class ContainerInspect(
    @SerialName("Id") val id: String,
    @SerialName("Name") val name: String? = null,
    @SerialName("State") val state: State = State(),
    @SerialName("Config") val config: Config = Config(),
) {
  @Serializable
  data class State(
      @SerialName("Status") val status: String? = null,
      @SerialName("Running") val running: Boolean = false,
      @SerialName("ExitCode") val exitCode: Int = 0,
      @SerialName("OOMKilled") val oomKilled: Boolean = false,
      @SerialName("Error") val error: String? = null,
  )

  @Serializable
  data class Config(
      @SerialName("Image") val image: String? = null,
      @SerialName("Labels") val labels: Map<String, String> = emptyMap(),
      @SerialName("Tty") val tty: Boolean = false,
  )
}
