# Node image/template

A **node** is a machine — cloud VM or manually attached static VM — that runs
workloads: Docker plus the `workload` CLI, registered with the broker as a
worker. This directory holds the single source of truth for what a node's
first boot looks like.

## One template, two renderers

[`cloud-init/node.yaml.tmpl`](cloud-init/node.yaml.tmpl) is a `#cloud-config`
template with exactly three placeholders — `${workload_environment}`,
`${node_name}`, `${cli_version}` — that installs Docker, a JRE, the
`workload` CLI, and a systemd service (`workload-node-identity-sync`) that
redeems whatever's on the node's identity volume. Nothing else in the file
uses `${...}`: every other `$` is a literal shell variable meant to expand on
the node at boot (see the template's header comment for why that matters —
it's what lets both renderers below fill in the same file without fighting
over syntax).

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

## `workload node create --driver utm` (local UTM VMs, macOS)

`utm` is a convenience layer over the exact same manual flow above, scripted
against a local [UTM.app](https://mac.getutm.app) instance instead of a human:

```
workload node create --driver utm --name my-node --utm-template ~/vms/node-template.utm
```

It mints the enrollment token and renders the boot/identity media exactly like
`--driver none`, then clones `--utm-template` (a `.utm` bundle you build once
by hand — base OS installed, two empty removable CD-ROM drives named
`boot.iso` / `identity.iso` in its `Images/` directory), swaps in the freshly
rendered ISOs, registers the clone with UTM, and starts it — see
[`UtmDriver.kt`](../cli/src/main/kotlin/software/medusa/workload/cli/UtmDriver.kt).
It shells out to `utmctl` for start/stop/status and to AppleScript only for the
one thing `utmctl` can't do (registering a newly cloned bundle).

Once created, the VM is controlled the same way:

```
workload node status --name my-node
workload node stop --name my-node
workload node start --name my-node
workload node rotate-identity --name my-node   # mint + swap a fresh identity volume
```

`rotate-identity` stops the VM, swaps `identity.iso`, and restarts it — **not**
a live swap. See [`utm-driver-spike.md`](utm-driver-spike.md) for why: neither
`utmctl` nor UTM's AppleScript support exposes a way to change a running VM's
removable-drive media, only the QEMU monitor UTM keeps internal does, and
nothing scripts that today.

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
