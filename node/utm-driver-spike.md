# Spike: can the identity-volume swap be scripted live on UTM?

`workload node create --driver utm` (see [`UtmDriver.kt`](../cli/src/main/kotlin/software/medusa/workload/cli/UtmDriver.kt))
is a convenience layer over the manual attach flow in [`node/README.md`](README.md): it drives a
local [UTM.app](https://mac.getutm.app) instance instead of asking a human to click through the
GUI. Node identity itself is designed to be swappable without a reboot — a second, small,
removable volume (`WLIDENTITY`) a udev rule re-triggers `workload-node-identity-sync` on whenever
it's (re-)attached, see the identity-volume section of `node/README.md`. This spike asks: can the
`utm` driver do that swap live, the same way a human would eject/insert a CD-ROM in the UTM GUI
without stopping the VM — or does it have to stop the VM first?

## What UTM actually exposes for scripting

Two surfaces, both investigated:

1. **`utmctl`** — the CLI UTM ships for automation. Its subcommands are `list`, `status`,
   `start`, `stop`, `suspend`, `attach`/`detach` (USB devices only), `ip-address`, `usb`. None of
   these operate on a VM's CD-ROM/removable-drive devices — `attach`/`detach` are scoped to USB
   passthrough, not to swapping a QEMU block device's backing image. There is no
   `utmctl <verb> <vm> <drive> <new-image>` equivalent of "eject this CD, insert that one."
2. **AppleScript** — UTM answers the generic Apple Event `open` (every document-based Mac app
   does; this is what the driver uses to register a freshly cloned `.utm` bundle in
   [`registerWithUtm`](../cli/src/main/kotlin/software/medusa/workload/cli/UtmDriver.kt)). It does
   not expose a scripting dictionary for a *running* VM's individual devices — there's no AppleScript
   verb for "change this VM's removable drive N to file X" the way there is for, say, iTunes/Music
   changing tracks. The only thing scriptable at the VM level is the same start/stop/suspend
   lifecycle `utmctl` already covers.

Neither surface reaches the one thing that would matter for a live swap: telling the running
QEMU process backing the VM to change a block device's media, the way QEMU's own monitor protocol
(`change <drive> <file>`) can. UTM doesn't expose that monitor to `utmctl` or AppleScript callers —
it's internal to the app.

## Conclusion

**No live swap is scriptable today.** [`UtmNodeVmDriver.rotateIdentity`](../cli/src/main/kotlin/software/medusa/workload/cli/UtmDriver.kt)
implements the fallback instead: stop the VM (if running) → replace `identity.iso` in the `.utm`
bundle's `Images/` directory → start it again. That's the same three steps
`workload node rotate-identity --driver utm` performs. This is not a *worse* rotation than a live
swap from the node's point of view — the udev rule that redeems a re-attached `WLIDENTITY` device
fires on boot too, so the node re-registers itself exactly the same way — it's just a VM restart
(tens of seconds) instead of a device re-attach with no interruption.

If UTM ever exposes a monitor/QEMUFile scripting hook (there's an open feature request upstream
for `utmctl` drive control), `rotateIdentity`'s stop/start bracket in `UtmNodeVmDriver` is the only
place that would need to change — the CLI-facing contract
(`workload node rotate-identity --driver utm`) already treats "how the swap happens" as an
implementation detail of the driver, not something callers depend on.

## What this means for the manual (`--driver none`) flow

Nothing changes there — `node/README.md`'s existing instructions ("detach `identity.iso`, attach a
freshly built one") already describe a human doing this through their own hypervisor's GUI, live,
without restarting the VM. That's still true for hypervisors whose GUI *does* support hot-swapping
removable media (UTM's own GUI included — the limitation found here is in what's *scriptable*, not
in what UTM can do interactively). The `utm` driver's fallback is specifically about automation,
not a new limitation of UTM itself.
