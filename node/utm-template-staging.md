# Staging `node create --driver utm`'s own base image and template

Before this, `workload node create --driver utm` required an operator to hand-build a template
`.utm` bundle first (download an Ubuntu cloud image, wrap it in a bundle via the UTM.app GUI, add
two empty removable CD-ROM drives named `boot.iso` / `identity.iso`) — the one manual step standing
between `--driver utm` and true plug-and-play. This note covers what staging does instead, and
where the risk in it is concentrated.

## What's staged, and how it's cached

Two steps, both skipped entirely if `--utm-template <path>` is given (the pre-existing
bring-your-own-template escape hatch — see [`NodeCommand.kt`](../cli/src/main/kotlin/software/medusa/workload/cli/NodeCommand.kt)):

1. **Fetch + cache the base disk** — [`ImageCache`](../cli/src/main/kotlin/software/medusa/workload/cli/UtmImageCache.kt).
   Default: the current Ubuntu LTS server cloud image
   (`https://cloud-images.ubuntu.com/<release>/current/<release>-server-cloudimg-<arch>.img`) for
   the host's arch (`arm64` on Apple Silicon, `amd64` otherwise — `hostImageArch`), checksum-verified
   against that release's `SHA256SUMS`. `--image-release` overrides the codename; `--base-image`
   overrides the whole resolution with an arbitrary URL or local path (URLs are cached verbatim, no
   checksum — there's no standard listing to verify an arbitrary URL against). Everything lands in
   `~/.cache/workload/images/`, keyed by file name with a sidecar `.sha256`; a cache hit is only
   trusted if the sidecar still matches the file's actual on-disk contents, so a prior partial or
   corrupted download can't poison a later create silently.
2. **Assemble the template** — [`UtmTemplateAssembler`](../cli/src/main/kotlin/software/medusa/workload/cli/UtmTemplateAssembler.kt).
   Builds a `.utm` bundle directory around the resolved disk (`Images/<disk file>` plus two empty
   `Images/boot.iso` / `Images/identity.iso` placeholders and a `config.plist`) under
   `~/.cache/workload/images/templates/<content-hash>.utm`, keyed by the disk's own SHA-256 (not its
   file name) so reusing a cached image under a different name — including a `--base-image` override
   that happens to collide with a cached default image's file name — can't silently reuse a stale
   template built from different bytes.

From there, nothing changes: [`UtmNodeVmDriver.create`](../cli/src/main/kotlin/software/medusa/workload/cli/UtmDriver.kt)
clones the assembled bundle exactly like it would a hand-built one, overwrites its two removable
drives with the node's real rendered `boot.iso` / `identity.iso`, registers it with UTM, and starts
it. Both staging steps are idempotent and reused across every later `node create --driver utm` —
the base image is downloaded once, and the template built from it is assembled once.

## What's *not* verified here: `config.plist`'s schema

`ImageCache` and `UtmTemplateAssembler`'s directory/file assembly is exercised by
`UtmImageCacheTest` / `UtmTemplateAssemblerTest` (structural: right files, right cache-key
behavior, right idempotence) — real, deterministic behavior that doesn't depend on UTM itself.

The one piece that does depend on UTM is `config.plist`'s contents:
`UtmTemplateAssembler.renderConfigPlist` writes a UTM "configuration version 4" QEMU-backend
property list — the schema recent UTM.app releases (4.x) use — declaring the disk as a `VirtIO`
drive and the two removable media as `USB`-interface `CD` drives, `aarch64`/`virt` or
`x86_64`/`q35` System settings depending on arch, and UEFI boot enabled (Ubuntu's cloud images boot
via UEFI + cloud-init's `NoCloud` datasource off the attached `boot.iso`, the same way the manual
flow already assumed). This was written from UTM's documented configuration shape, not verified
against a running UTM.app — there's no macOS/UTM available in this environment to open the
assembled bundle and confirm it boots.

If a UTM release rejects this `config.plist` (a renamed key, a different `Target` string for the
QEMU version that release bundles, a different expected `Interface` for removable media), the fix
is isolated to `renderConfigPlist` — nothing in `ImageCache`, the caching/keying logic around it, or
`UtmNodeVmDriver`'s clone/attach/start flow needs to change, the same way
[`utm-driver-spike.md`](utm-driver-spike.md) isolates its own UTM-version risk to
`rotateIdentity`'s stop/swap/start bracket. `--utm-template` remains available as an immediate
workaround: hand-build (or hand-fix) one bundle in UTM.app's GUI and point `--driver utm` at it,
same as before this staging existed.
