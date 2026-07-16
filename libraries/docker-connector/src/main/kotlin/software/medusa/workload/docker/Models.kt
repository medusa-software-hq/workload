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
