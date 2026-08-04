# workload CLI

Two planes: the **worker** side registers this machine with the broker, claims a
profile's short-lived credentials, and runs work under them; the **admin** side
manages profiles, authenticated with your own Google sign-in.

## Worker commands

Worker operations live under the `workload worker` group.

| Command | What it does |
| --- | --- |
| `workload worker register` | Registers this machine by redeeming a one-time enrollment token (`--enrollment-token`, or prompted) an admin generated in the console. |
| `workload worker status` | Shows this worker's status and granted profiles. |
| `workload worker token -p <profile>` | Prints a brokered access token for a profile. |
| `workload worker exec -p <profile> -- <cmd>` | Runs a **local command** with the profile's env injected. |
| `workload worker run -p <profile>` | Runs the profile's **container image**, streaming its output. |
| `workload worker ps` | Lists containers workload started here; `--reap` stops and removes them. |
| `workload worker unregister` | Removes this worker's local registration. |

## Admin commands

Admin operations live under the `workload admin` group and act on the profile
management API — the same one the console SPA drives.

| Command | What it does |
| --- | --- |
| `workload admin login` | Signs in with your medusa.software Google account (opens a browser) and caches the session. |
| `workload admin logout` | Forgets the cached session on this machine. |
| `workload admin profiles list` | Lists all profiles. |
| `workload admin profiles show <id>` | Shows a profile's latest revision (`--revision N` for one; `--json` emits the create/update spec). |
| `workload admin profiles create <id> -f <spec.json\|->` | Creates a profile from a JSON revision spec (`--display-name` optional; `-` reads stdin). |
| `workload admin profiles update <id> -f <spec.json\|->` | Adds a new revision to a profile from a JSON spec. |
| `workload admin profiles verify <id>` | Re-verifies a profile's latest revision against live GCP. |
| `workload admin profiles archive <id>` | Archives a profile (no longer grantable). |
| `workload admin profiles grant <profile> <worker>` | Grants a profile to a worker. |
| `workload admin profiles revoke <profile> <worker>` | Revokes a worker's grant of a profile. |
| `workload admin workers list` | Lists all workers. |
| `workload admin workers approve\|reject\|revoke <id>` | Moves a worker through its registration lifecycle. |
| `workload admin runs list` | Lists in-flight runs (`--profile`/`--worker` to filter, `--all` for finished ones, `--watch` to poll, `--json` for scripts). |

`profiles show --json` emits the same spec shape `create` / `update` accept, so
the natural round-trip works:

```bash
workload admin profiles show hand-test-1 --json > p.json   # dump the latest revision's spec
$EDITOR p.json                                              # tweak SA / image / env / secrets
workload admin profiles update hand-test-1 -f p.json        # ...as a new revision
# or pipe it straight through:
workload admin profiles show hand-test-1 --json | workload admin profiles create clone-1 -f -
```

The spec is just the mutable fields — `targetServiceAccount` (required), `note`,
`dockerImage`, `envVars`, `secretEnvVars`. The image tag's digest is resolved and
pinned server-side; `create`/`update` print the digest they pinned.

`admin login` uses the standard installed-app OAuth flow: it opens your browser,
you sign in as yourself, and it captures the result on a one-shot `127.0.0.1`
loopback (PKCE-protected). It caches a **refresh token** under
`~/.config/ms-workload/admin.json` (0600) and mints a fresh ID token for each
call, so you only sign in occasionally — not every command. The API accepts you
because your token carries the `medusa.software` hosted-domain claim; a service
account can't reach this plane (see the repo README's auth notes).

> **This is human, not machine, auth.** The cached refresh token is your standing
> admin access — treat `~/.config/ms-workload/admin.json` accordingly, and
> `workload admin logout` to clear it.

`exec` and `run` are the two ways to do work:

- **`exec`** needs no image — it runs a command already installed on this host.
- **`run`** requires the profile to have a container image. If it doesn't, `run`
  tells you to use `exec` instead.

## Host prerequisites for `workload worker run`

**A Docker daemon. That's the whole list.**

Not `gcloud`, not `gcloud auth configure-docker`, not `docker login` — and not
the `docker` CLI itself, which `ms-workload` never invokes. The image pull
authenticates with the **brokered token** from the claim (the profile's own
target service account), so a fresh machine just works and nothing is written to
`~/.docker/config.json`.

The daemon is found the way Docker itself decides, by reading the same files —
never by shelling out to `docker context`:

1. `DOCKER_HOST` (a `unix://` socket), if you set it.
2. The active `docker context` — `DOCKER_CONTEXT`, else `currentContext` from
   `~/.docker/config.json`. This is what makes **Docker Desktop** and **Colima**
   work with no configuration.
3. Well-known paths: `/var/run/docker.sock` (stock Ubuntu, tried first),
   `$XDG_RUNTIME_DIR/docker.sock` (rootless), then Docker Desktop's and Colima's
   sockets under `$HOME`.

A `tcp://` or `ssh://` `DOCKER_HOST`/context is a clear error rather than a
silent fallback — running your workload against a different daemon than you
asked for would be worse than refusing. If your user isn't in the `docker`
group, the error says so.

The permission that matters is on the *profile*, not on your machine: the
target service account needs `roles/artifactregistry.reader` on the image's
repository, granted via the
[workload-impersonation module](../infra/modules/workload-impersonation)'s
`artifact_repository_id` input. If it's missing, the console flags the revision
at verification and the profile isn't claimable — so you get a clear error up
front rather than a 403 in the middle of a pull.

`workload worker run` deliberately **won't** fall back to your machine's own Docker
login if the brokered pull is denied. That would mask a broken grant: the
profile would work on whichever laptop happened to be signed in, and fail
everywhere else.

> Profile images must live in a Google container registry (`*.pkg.dev`,
> `gcr.io`, `*.gcr.io`). That's a security rule — the pull sends a live token
> for the target service account to the image's registry, so Workload will only
> ever send it to Google.

## What `workload worker run` does

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
is the accepted teardown boundary, and `workload worker ps` is the mitigation:

```console
$ workload worker ps
CONTAINER     PROFILE      REV  STATE    STATUS                 AGE
082f30f0376b  my-profile   1    running  Up 4 minutes           4m

$ workload worker ps --reap     # stops + removes; asks first (-y to skip)
```

`ps` finds containers by the `ms-workload.*` labels every run applies, so it
only ever touches containers workload started. It cannot tell an orphan from a
run in progress in another terminal — there's no heartbeat — so `--reap` warns
about running containers and asks before doing anything.

## Development

```bash
task cli:compile   # or: task cli:test, task cli:lint
task cli:run -- --help
```

Tests include contract tests against a real Docker daemon; they self-skip when
no daemon is reachable, and are required to run in CI.
