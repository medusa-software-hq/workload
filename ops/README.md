# ops

Operational tooling for people, not services — scripts a human runs by hand against a real
environment. `infra/` is Terraform (declared state); this is the imperative stuff Terraform
doesn't cover.

## roll-worker

Phase 0 of the **automated-rollout epic** (workload#126, see `PROMOTION.md` and
`backend/version-drift-checker/README.md` for the rest of that epic).

**This is now the supported way to roll a hosted Flow worker onto a new image** — it supersedes
the ad-hoc "edit the profile digest by hand, SSH in, tmux kill + recreate" steps in the hub's
`vm.md`. If you're looking at `vm.md` for that runbook, use `roll-worker` instead; `vm.md` should
be updated to point here.

```console
$ ops/roll-worker staging                              # bump to latest-published, wait, recycle
$ ops/roll-worker prod sha256:abcd…                     # pin an explicit digest
$ ops/roll-worker prod sha256:abcd… --force             # skip the wait-for-idle poll
```

### What it does

Three steps, in order — see the script's header comment for the full option/env-var reference:

1. **Bump.** Adds a new revision to the `flow-worker` profile (`--profile` to target a different
   one). With a `digest` argument, the revision is pinned to exactly that digest; omitted, the
   profile's existing image tag is re-resolved fresh server-side — the "latest-published" digest,
   the same resolution `admin profiles update` always does when no digest is given.
2. **Wait for idle.** Polls `workload admin runs list --profile <profile> --json` until no run is
   `RUN_STATE_RUNNING` for that profile in that environment — i.e., no in-flight Flow session on
   the worker being rolled. `--force` skips this and recycles immediately; use it for an
   out-of-band emergency roll, not routine ones. This is the whole point of the script: a routine
   roll should never abandon an in-flight session.
3. **Replace.** SSHes to the environment's worker host and recycles the tmux session running the
   worker loop, so it re-claims the profile and pulls the digest the bump just pinned.

The bump → wait-for-idle → replace body is deliberately kept as one self-contained loop, not
folded into other tooling: it's the same loop a future reconciling agent (Phase 3 of the epic) is
meant to absorb wholesale.

### Prerequisites

- The `workload` CLI, installed and signed in (`workload admin login`) as an admin for the target
  environment. `WORKLOAD_CLI` points the script at a specific binary if `ms-workload` isn't the
  one on `PATH` (e.g. a local build via `task cli:run` — see `cli/README.md`).
- `jq` and `ssh`.
- `ROLL_WORKER_SSH_HOST` set to the environment's hosted-worker host (ask in the ops channel or
  check the hub's inventory if you don't have it handy — this is deliberately not hard-coded here,
  the same way no other environment secret is). `ROLL_WORKER_SSH_USER` and
  `ROLL_WORKER_TMUX_SESSION` have sane defaults (current user; the profile name) but are
  overridable.

### Why "recycle the tmux session" and not "re-register the worker"

`workload worker run` pulls `<repo>@<digest>` fresh on every claim (see `cli/README.md`), so the
worker doesn't need to forget and re-register — restarting the loop that calls `run` is enough for
it to pick up the newly pinned digest on its next claim. Revoking/re-registering would be a bigger
hammer than the digest bump calls for, and would needlessly churn the worker's identity.
