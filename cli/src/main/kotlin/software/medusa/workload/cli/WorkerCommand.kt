package software.medusa.workload.cli

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.NoOpCliktCommand

/**
 * Groups the worker-side operations — registration, token brokering, and container lifecycle —
 * under a `worker` prefix. A sibling to the (forthcoming) `admin` group, which drives the profile
 * management API via a human OAuth sign-in.
 */
class WorkerCommand : NoOpCliktCommand(name = "worker") {
  override fun help(context: Context) =
      "Worker-side operations: registration, token brokering, and container lifecycle."
}
