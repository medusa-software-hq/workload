# node-template

Renders `node/cloud-init/node.yaml.tmpl` — the single unified node cloud-init
template — as `google_compute_instance` user-data, via Terraform's
`templatefile()`. This is one of two renderers of that same file; the other is
`node/cloud-init/render-iso.sh`, which burns it onto a boot ISO for a
manually-attached VM. See `node/README.md` for the full picture, including the
swappable identity-volume mechanism this module deliberately stays out of.

## Usage

```hcl
module "node_template" {
  source = "../../infra/modules/node-template"

  workload_environment = "prod"
  node_name             = "gce-worker-1"
  cli_version           = "latest"
}

resource "google_compute_instance" "node" {
  name         = "gce-worker-1"
  machine_type = "e2-small"
  zone         = "us-central1-a"

  boot_disk {
    initialize_params { image = "debian-cloud/debian-12" }
  }

  network_interface { network = "default" }

  metadata = {
    user-data = module.node_template.user_data
  }

  # The identity volume: a small disk with a single WLIDENTITY-labeled
  # filesystem holding `enrollment-token`, built the same way
  # render-identity-volume.sh builds one for a static VM. Swap it (detach,
  # attach a freshly-minted one, no instance recreate) to rotate this node's
  # credential without a reboot — see node/README.md.
  attached_disk {
    source = google_compute_disk.node_identity_volume.self_link
  }
}
```

The `workload node create --driver none` / `workload node enroll` CLI flow
mints the enrollment token this identity volume carries; wiring a `--driver
gce` that also creates the `google_compute_instance` above (using this module)
is the natural next step, left for when a cloud driver is actually needed —
today's only driver is manual attachment.
