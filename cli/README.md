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

> **Temporary.** These are the stage-2 requirements. `workload run` no longer
> invokes the `docker` CLI at all — the pull goes through the library — but it
> still rides this host's user-global Docker sign-in, which means a credential
> *helper* binary. See [plan/m3](../../plan/m3) for the migration ladder.

1. **Docker** — a running daemon this user can reach. `workload run` talks to
   `/var/run/docker.sock` directly (override with `DOCKER_HOST=unix:///path`).
   If your user isn't in the `docker` group, `docker info` will tell you.
2. **A one-time registry sign-in.** `workload run` deliberately uses **no
   broker-specific Docker auth** — it reads `~/.docker/config.json` and uses
   whatever credential helper is configured there, exactly as `docker` itself
   would. For an Artifact Registry image, once per host:

   ```bash
   gcloud auth login                                    # if you aren't already
   gcloud auth configure-docker us-docker.pkg.dev --quiet   # match the image's region
   ```

   Use the registry host from the profile's image ref (e.g.
   `europe-docker.pkg.dev` for a `europe-docker.pkg.dev/...` image). If a pull
   fails on auth, `workload run` prints this exact command for the right host.

   That command writes a `credHelpers` entry into `~/.docker/config.json` and
   installs `docker-credential-gcloud`. The **helper binary** is what
   `workload run` executes — it is a separate program from the `docker` CLI,
   which is no longer needed. Credential resolution order matches Docker's:
   `credHelpers[registry]` → `credsStore` → a static `auths` entry → anonymous
   (so public images pull with no setup at all).

Your Google identity needs read access to the image's repository. Note this is
*separate* from the profile's target service account, which needs
`roles/artifactregistry.reader` so the **backend** can resolve the image digest —
see the [workload-impersonation module](../infra/modules/workload-impersonation).

## What `workload run` does

1. Preflights the daemon.
2. Claims the profile — token, env vars, secret refs, and the image
   (`ref` + the `digest` the revision pinned at creation).
3. Resolves secret env vars directly against Secret Manager using the brokered
   token (values never pass through the broker).
4. Pulls `<repo>@<digest>` through the library — **the pinned digest, not the
   tag**, so a tag that has moved since the revision was created can't change
   what runs. An already-cached digest makes this a fast no-op.
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
