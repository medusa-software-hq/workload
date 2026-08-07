# Node image/template

A **node** is a machine — cloud VM or manually attached static VM — that runs
workloads: Docker plus the `workload` CLI, registered with the broker as a
worker. This directory holds the single source of truth for what a node's
first boot looks like.

## One template, two renderers

[`cloud-init/node.yaml.tmpl`](cloud-init/node.yaml.tmpl) is a `#cloud-config`
template with exactly three placeholders — `${workload_environment}`,
`${node_name}`, `${cli_version}` — that installs Docker, a JRE, the
`workload` CLI, a systemd service (`workload-node-identity-sync`) that
redeems whatever's on the node's identity volume, and a second systemd
service (`workload-agent`) that runs the always-on reconciler daemon —
`workload-agent`'s own jar, ordered to start after identity sync so it never
races an unregistered node. Nothing else in the file uses `${...}`: every
other `$` is a literal shell variable meant to expand on the node at boot
(see the template's header comment for why that matters — it's what lets
both renderers below fill in the same file without fighting over syntax).

It's rendered two ways:

1. **Terraform**, for a cloud VM — [`infra/modules/node-template`](../infra/modules/node-template)
   calls `templatefile()` on this same file and hands the result to a
   `google_compute_instance`'s `user-data` metadata key.
2. **An ISO seed image**, for a manually attached (static) VM —
   [`cloud-init/render-iso.sh`](cloud-init/render-iso.sh) `sed`-substitutes the
   same three placeholders and, if `genisoimage`/`mkisofs`/`xorriso` is
   available, burns a NoCloud seed ISO (volume id `cidata`) that any
   cloud-init-aware hypervisor boots from directly.

`workload node enroll` / `workload node create --driver none` (see below) do
this same rendering from inside the CLI jar — no shell tooling required — so
a plain `workload` install is enough to provision a node end to end.

## The identity volume — swappable, no reboot

Node identity is **never** baked into the template above. It lives on a
second, small, separate volume: a block device labeled `WLIDENTITY` holding
one file, `enrollment-token`. The template installs:

- `workload-node-identity-sync` — mounts that device read-only, redeems the
  token via `workload worker register --force` (non-interactively, using the
  `WORKER_ENROLLMENT_TOKEN` env var the CLI already supports), and exits.
- A boot-time systemd unit that runs it once.
- A udev rule that re-runs it **every time a `WLIDENTITY` device is
  (re-)attached** — so an operator rotates a node's identity by minting a
  fresh enrollment token, building a new identity volume
  ([`cloud-init/render-identity-volume.sh`](cloud-init/render-identity-volume.sh)
  or `workload node enroll --identity-only`), and swapping the volume on the
  running VM. No reboot, no recreating the instance — the CLI reads
  `config.json` fresh on every invocation, so the swap takes effect the next
  time anything on the node calls `workload worker ...`.

This is also why a cloud VM's Terraform-rendered boot media and a static VM's
ISO are interchangeable: both boot the exact same image; only how the
identity volume gets attached differs (an `attached_disk` block vs. a second
CD-ROM in a hypervisor).

## `workload node enroll` / `workload node create --driver none`

Both live under `workload node` (admin-authenticated, since minting an
enrollment token is an admin action):

```
workload node enroll --name my-node
workload node create --driver none --name my-node
```

Both mint a one-time enrollment token (the same `CreateEnrollmentToken` admin
API `workload admin enrollment create` uses), render the node's boot media and
identity volume into `./node-<name>/`, build ISOs for both if an ISO tool is
on `PATH`, and print the manual attach steps. `create` is the seam for a
future cloud driver (e.g. `--driver gce`, which would additionally call
`infra/modules/node-template` and the Google API to create the instance
itself); `none` is what exists today — attach the media to a VM yourself.

`--identity-only` mints and renders just a fresh identity volume, for
rotating an already-running node without touching its boot media at all.

## `workload node create --driver vz` (local Virtualization.framework VMs, macOS/Apple Silicon)

`vz` is the primary local-node driver: it runs the node under Apple's
Virtualization.framework via [`vfkit`](https://github.com/crc-org/vfkit) (Red
Hat's headless VZ wrapper — the same one `podman machine`/`crc`/minikube use),
instead of the older `utm` driver below. Where `utm` drives a GUI app's own
on-disk VM-library format (`utmctl`/AppleScript against a `.utm` bundle —
fragile across UTM versions, and a foot-gun in practice: no bundled ISO
tooling, a cloned bundle UTM won't recognize until renamed correctly, and a
`config.plist` schema that drifts), `vfkit` *is* the running VM: a single
process this driver launches and controls directly, with a REST API over a
unix socket for start/stop/status. There's no GUI app, no VM-library format,
and no bundle-layout mismatch to get wrong.

```
workload node create --driver vz --name my-node
```

It mints the enrollment token and renders the boot/identity media exactly like
`--driver none`, stages a raw base disk (the cached Ubuntu LTS cloud image for
the host arch by default, same [`ImageCache`](../cli/src/main/kotlin/software/medusa/workload/cli/UtmImageCache.kt)
`--driver utm` uses — see `--base-image`/`--image-release` below), converts it
to the raw format Apple's Virtualization.framework requires (Ubuntu's cloud
images are qcow2 despite their `.img` extension; conversion is cached by the
source's content hash under `~/.cache/workload/images/raw/` — see
[`VfkitDiskStager.kt`](../cli/src/main/kotlin/software/medusa/workload/cli/VfkitDiskStager.kt)),
then launches `vfkit` with the disk and the two rendered ISOs attached as
virtio-blk devices and a unix-socket REST API for control — see
[`VfkitDriver.kt`](../cli/src/main/kotlin/software/medusa/workload/cli/VfkitDriver.kt).
A node's whole state (disk, ISOs, EFI variable store, console log, socket)
lives under `~/.workload/vz-nodes/<name>/`.

`--base-image`/`--image-release` are the same flags `--driver utm` takes (see
below); `--vfkit` points at the `vfkit` binary itself if it isn't on PATH
(`brew install vfkit`).

Once created, the VM is controlled the same way as any driver-managed node:

```
workload node status --name my-node --driver vz
workload node stop --name my-node --driver vz
workload node start --name my-node --driver vz
workload node rotate-identity --name my-node --driver vz
```

`vz` is the default driver for `node start`/`stop`/`status`/`rotate-identity`
(pass `--driver utm` explicitly for a `utm`-managed node). Like `utm`,
`rotate-identity` stops the VM, swaps `identity.iso`, and restarts it — not a
live swap: `vfkit`'s REST API has no "change this device's backing file" verb
any more than `utmctl`/AppleScript do (see node/utm-driver-spike.md), so both
drivers fall back to the same stop/swap/start bracket.

## `workload node create --driver utm` (local UTM VMs, macOS, legacy)

**Superseded by `--driver vz` above** — kept working for anyone with an
existing UTM-based setup or who needs UTM's GUI (e.g. to watch the console),
but no longer the recommended local driver. `utm` is a convenience layer over
the exact same manual flow above, scripted against a local
[UTM.app](https://mac.getutm.app) instance instead of a human — and, unlike
the rest of this section's earlier revisions, plug-and-play: it needs nothing
pre-staged beyond UTM.app itself.

```
workload node create --driver utm --name my-node
```

It mints the enrollment token and renders the boot/identity media exactly like
`--driver none`, then **stages its own template**: fetches (or reuses a cached
copy of) the current Ubuntu LTS cloud image for the host arch, checksum-verified
against Ubuntu's `SHA256SUMS`, and assembles a `.utm` bundle around it — base
disk plus two empty removable CD-ROM drives named `boot.iso` / `identity.iso` in
its `Images/` directory. It then clones that template, swaps in the freshly
rendered ISOs, registers the clone with UTM, and starts it — see
[`UtmDriver.kt`](../cli/src/main/kotlin/software/medusa/workload/cli/UtmDriver.kt),
[`UtmImageCache.kt`](../cli/src/main/kotlin/software/medusa/workload/cli/UtmImageCache.kt),
[`UtmTemplateAssembler.kt`](../cli/src/main/kotlin/software/medusa/workload/cli/UtmTemplateAssembler.kt),
and [`utm-template-staging.md`](utm-template-staging.md) for what's staged, how
it's cached (`~/.cache/workload/images/`, downloaded/assembled at most once and
reused across creates), and the one part of it (the assembled bundle's
`config.plist`) that couldn't be verified against a real UTM.app in this
environment.  It shells out to `utmctl` for start/stop/status and to AppleScript
only for the one thing `utmctl` can't do (registering a newly cloned bundle).

Three flags tune or bypass staging:

- `--image-release <codename>` — Ubuntu release to stage (e.g. `noble`); defaults
  to the current LTS.
- `--base-image <url|path>` — use a different base disk (custom/prebuilt) instead
  of the default Ubuntu cloud image.
- `--utm-template <path>` — the original bring-your-own-template flow: skips
  staging entirely and clones this pre-built `.utm` bundle instead (base OS
  already installed, two empty removable CD-ROM drives named `boot.iso` /
  `identity.iso` in its `Images/` directory) — still the fallback if staging's
  assembled `config.plist` doesn't work against your UTM version.

Once created, the VM is controlled the same way (note `--driver utm` is now
required on these — `vz` is the default for `node start`/`stop`/`status`/
`rotate-identity`):

```
workload node status --name my-node --driver utm
workload node stop --name my-node --driver utm
workload node start --name my-node --driver utm
workload node rotate-identity --name my-node --driver utm   # mint + swap a fresh identity volume
```

`rotate-identity` stops the VM, swaps `identity.iso`, and restarts it — **not**
a live swap. See [`utm-driver-spike.md`](utm-driver-spike.md) for why: neither
`utmctl` nor UTM's AppleScript support exposes a way to change a running VM's
removable-drive media, only the QEMU monitor UTM keeps internal does, and
nothing scripts that today.

## The real cloud fallback node (workload#122 part 4)

`infra/gcp-fallback-node.tf` (in the top-level `infra/` root; `enable_fallback_node = true` to
stand it up — see that file's header comment) is the one real consumer of the Terraform "cloud
mode" renderer above: an always-on e2-micro GCE VM, boot media rendered by
`infra/modules/node-template`, identity volume built by
[`build-gce-identity-image.sh`](cloud-init/build-gce-identity-image.sh) (GCE has no removable
CD-ROM the way UTM does — see that script's header for how the swappable-identity-volume design
maps onto a GCE persistent disk instead) from a token `workload node enroll --name
fallback-node --identity-only` mints, and its own GCE service account allow-listed on
`GCE_NODE_SERVICE_ACCOUNTS` (`backend/infra/gcp-service.tf`) alongside the pre-existing
enrollment-token registration path.

`node.yaml.tmpl`'s `runcmd` also provisions a 2G swapfile unconditionally (harmless on a bigger
node, load-bearing on e2-micro's 1 GiB) — see the template's own comment.

Once the VM registers, an admin flags it via the `SetWorkerFallbackNode` admin RPC (and tags the
target profile(s) `fallback_eligible` via `SetProfileFallbackEligible`) — Terraform stands the
machine up; it doesn't touch the fleet-store row the node becomes, same as every node this repo
provisions. Neither RPC has a CLI subcommand or console control yet (only `ListWorkers`/
`ListProfiles` surface the flags today) — call them directly against the admin gRPC endpoint (see
`AdminClient.setWorkerFallbackNode`/`setProfileFallbackEligible` in `system-test` for the exact
request shape) until that lands. From there the migrate-on-arrival behavior is exactly
`FallbackPlacementReconciler`'s: a fallback-eligible profile with no dedicated node runs here, and
migrates off automatically the instant a dedicated node (e.g. a developer's laptop) is granted it —
proven end to end against the real backend by
`system-test/.../FallbackMigrationSystemTest.kt`, and against the real node by `ops/roll-worker`'s
neighbors `ops/measure-fallback-node-idle-memory` (idle footprint) and
`ops/verify-multi-arch-on-node` (the multi-arch images `workload run` depends on actually resolve
and run on real e2-micro/amd64 hardware, not just in CI) — see `ops/README.md`.

## Acceptance walkthrough

- **Rendering the template both ways yields a bootable node.** Both
  `infra/modules/node-template`'s `templatefile()` output and
  `render-iso.sh`'s `sed` output substitute the same three placeholders in the
  same file — `NodeCommandTest` asserts the bundled template renders with no
  placeholders left over and the identity-sync machinery present either way.
- **A manually-attached VM enrolls and shows up as a node.** Boot a VM from
  `node-seed.iso` with `identity.iso` attached as a second disk; cloud-init
  installs Docker + the CLI, `workload-node-identity-sync` runs at boot,
  redeems the token, and the node lands in `workload admin workers list`
  exactly like any other worker registration.
- **The node's identity can be swapped without recreating the VM.** Detach
  `identity.iso`, attach a freshly built one (new enrollment token); the udev
  rule fires `workload-node-identity-sync` again, which re-registers in
  place — the VM itself never restarts.
- **An enrolled node self-reconciles with no manual daemon launch.** Once
  `workload-node-identity-sync` has written a worker credential,
  `workload-agent.service` (already enabled at boot, retrying on a timer
  until that credential exists) starts converging: assign a profile via
  `admin assignments create`, and the node picks it up on its next
  reconcile pass — no SSH, no manually running the jar.
