# workload CLI

The worker-side CLI: registers this machine with the broker, claims a profile's
short-lived credentials, and runs work under them.

## Commands

| Command | What it does |
| --- | --- |
| `workload register` | Registers this machine; an admin approves it in the console. |
| `workload status` | Shows this worker's status and granted profiles. |
| `workload token -p <profile>` | Prints a brokered access token for a profile. |
| `workload exec -p <profile> -- <cmd>` | Runs a **local command** with the profile's env injected. |
| `workload run -p <profile>` | Runs the profile's **container image**, streaming its output. |
| `workload unregister` | Removes this worker's local registration. |

`exec` and `run` are the two ways to do work:

- **`exec`** needs no image — it runs a command already installed on this host.
- **`run`** requires the profile to have a container image. If it doesn't, `run`
  tells you to use `exec` instead.

## Host prerequisites for `workload run`

**Docker. That's the whole list.**

- A running daemon this user can reach. `workload run` talks to
  `/var/run/docker.sock` directly (override with `DOCKER_HOST=unix:///path`).
  If your user isn't in the `docker` group, `docker info` will tell you.

No `gcloud`, no `gcloud auth configure-docker`, no `docker login`, and not even
the `docker` CLI itself. The image pull authenticates with the **brokered token**
from the claim — the profile's own target service account — so a fresh machine
just works, and nothing is written to `~/.docker/config.json`.

The permission that matters is on the *profile*, not on your machine: the
target service account needs `roles/artifactregistry.reader` on the image's
repository, granted via the
[workload-impersonation module](../infra/modules/workload-impersonation)'s
`artifact_repository_id` input. If it's missing, the console flags the revision
at verification and the profile isn't claimable — so you get a clear error up
front rather than a 403 in the middle of a pull.

`workload run` deliberately **won't** fall back to your machine's own Docker
login if the brokered pull is denied. That would mask a broken grant: the
profile would work on whichever laptop happened to be signed in, and fail
everywhere else.

> Profile images must live in a Google container registry (`*.pkg.dev`,
> `gcr.io`, `*.gcr.io`). That's a security rule — the pull sends a live token
> for the target service account to the image's registry, so Workload will only
> ever send it to Google.

## What `workload run` does

1. Preflights the daemon.
2. Claims the profile — token, env vars, secret refs, and the image
   (`ref` + the `digest` the revision pinned at creation).
3. Resolves secret env vars directly against Secret Manager using the brokered
   token (values never pass through the broker).
4. Pulls `<repo>@<digest>` through the library, authenticating with the brokered
   token as `oauth2accesstoken` — **the pinned digest, not the tag**, so a tag
   that has moved since the revision was created can't change what runs. An
   already-cached digest makes this a fast no-op. One token, two uses: the same
   credential authenticates this pull and the workload's GCP access inside the
   container, so it's one identity end to end.
5. Creates the container with the env in the request body (never on a command
   line, so values can't appear in `ps`), labelled `ms-workload.profile`,
   `ms-workload.revision`, and `ms-workload.worker`.
6. Streams the container's stdout/stderr to yours, waits, and **exits with the
   container's exit code**.

Ctrl-C stops the container (SIGTERM, then SIGKILL after a grace period) and
removes it. If you `kill -9` the CLI itself, the container is left behind — that
is the accepted teardown boundary for now.

## Development

```bash
task cli:compile   # or: task cli:test, task cli:lint
task cli:run -- --help
```

Tests include contract tests against a real Docker daemon; they self-skip when
no daemon is reachable, and are required to run in CI.
