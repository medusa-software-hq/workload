package software.medusa.workload.server

import com.linecorp.armeria.server.ServiceRequestContext
import io.netty.util.AttributeKey

private object AdminPrincipalAttrMarker

/** Set by whichever auth decorator ran (Google-token auth in production, a fixed value locally). */
internal val adminPrincipalAttrKey: AttributeKey<Principal> =
    AttributeKey.valueOf(AdminPrincipalAttrMarker::class.java, "ADMIN_PRINCIPAL")

/** The verified principal for the request currently being served, or null if none was set. */
internal fun currentAdminPrincipal(): Principal? =
    ServiceRequestContext.current().attr(adminPrincipalAttrKey)

/** The acting admin's email for the request currently being served, for audit logging. */
internal fun currentAdminEmail(): String = currentAdminPrincipal()?.email ?: "unknown"

/** The acting admin's kind (human/service) for the request currently being served, for audit. */
internal fun currentAdminKind(): String = currentAdminPrincipal()?.kind ?: "unknown"

/** The caller's source IP for the request currently being served, for audit logging. */
internal fun currentSourceIp(): String =
    runCatching { ServiceRequestContext.current().clientAddress().hostAddress }
        .getOrDefault("unknown")
