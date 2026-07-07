# Workload

This is an internal project built from the Counter template.

## Architecture

Counter is a small full-stack template split into three main layers:

- **Frontend SPA** - a React/Vite application, served by Caddy
- **Backend API** - a Kotlin gRPC service built on Armeria
- **Shared contract** - protobuf definitions used to generate client and server code

At runtime, the flow is:

```text
Browser
  -> React SPA served by Caddy
  -> Google Identity Services for sign-in
  -> Kotlin API on Cloud Run
  -> Counter service implementation
  -> Counter storage
```

In production, the backend validates Google ID tokens and persists the counter in Neon (serverless Postgres), accessed through SQLDelight. For local development, the project swaps those pieces for a no-op auth layer and an in-memory store.

### Application design

The repository is organized as a multi-module project with clear separation between transport, business logic, and infrastructure:

- `apps/web/spa/frontend/` - browser application
- `backend/api/impl/shared/` - shared backend code
- `backend/api/impl/gcp/` - production backend entry point
- `backend/api/impl/local/` - local backend entry point
- `proto/` - gRPC and protobuf definitions
- `infra/` and per-module `infra/` directories - Terraform configuration
- `cli/` - a standalone Kotlin CLI, its own Gradle root

### Frontend

The frontend is a single-page application built with **React**, **Vite**, and **Mantine**.

Its main responsibilities are:

- render the UI
- authenticate the user with **Google Identity Services**
- cache and refresh the ID token
- call the backend through generated gRPC client code

The SPA has two auth modes:

- **Production auth** via `AuthProvider`, which manages Google sign-in and token refresh
- **Local auth** via `LocalAuthProvider`, which removes the external auth dependency for local development

The app is bundled with Vite and packaged into a container that serves static assets through **Caddy**, with SPA-style routing fallback to `index.html`.

### Backend

The backend is a **Kotlin + Armeria** server that exposes the `CounterService` gRPC API defined in `proto/medusa/counter/v1/counter_service.proto`.

The shared backend module contains the main building blocks:

- `CounterServiceImpl` - implements the gRPC service
- `CounterStore` - storage abstraction
- auth decorators - request authentication
- `buildServer(...)` - common server wiring

The service itself is intentionally thin. It exposes three RPCs:

- `GetCount`
- `Increment`
- `Decrement`

`CounterServiceImpl` delegates all state changes to `CounterStore`, which keeps the transport layer separate from persistence.

The server builder is responsible for:

- binding the HTTP port
- exposing `/health`
- registering the gRPC service
- enabling browser-friendly unframed requests
- applying CORS
- applying authentication

### Environments and dependency wiring

The backend has two entry points that wire the same shared service differently:

- **GCP / production**
    - uses `GoogleIdTokenAuthDecorator`
    - uses `PostgresCounterStore` (SQLDelight, backed by Neon)
    - reads configuration such as port, client ID, allowed Google Workspace domain, CORS origin regex, and the Neon `DATABASE_URL` from environment variables

- **Local development**
    - uses `NoOpAuthDecorator`
    - uses `InMemoryCounterStore`

This keeps environment-specific concerns at the edge while preserving a single shared application core.

### Storage

The primary persistence model is hidden behind `CounterStore`, which makes the service easy to swap between implementations.

Current implementations include:

- `InMemoryCounterStore` - simple local development store
- `PostgresCounterStore` - production store backed by Neon (serverless Postgres)

`PostgresCounterStore` uses **SQLDelight** for type-safe queries (generated from
`shared/src/main/sqldelight/.../Counter.sq`) and **Flyway** for runtime schema
migrations (`shared/src/main/resources/db/migration/`). SQLDelight owns the queries;
Flyway owns the schema.

This pattern keeps business logic independent from the underlying database choice.

### API contract

The API is defined in protobuf and versioned under `medusa.counter.v1`.

That contract is the boundary between frontend and backend:

- server code implements the generated Kotlin service base
- frontend code uses generated client stubs
- changes to the API are centralized in the proto definitions

Because the service is contract-first, transport and client generation stay consistent across modules.

### Infrastructure

Infrastructure is managed with **Terraform** and split by concern rather than kept in a single root module.

#### Root infrastructure (`infra/`)

The root Terraform project provisions shared platform resources such as:

- the GCP project
- Artifact Registry
- CI/CD service accounts
- GitHub integration
- shared DNS/domain mapping support

#### Backend infrastructure (`backend/infra/`)

The backend Terraform project provisions resources required by the API (and, in the
future, other backend services such as a worker), including:

- Cloud Run service
- Neon (serverless Postgres) project
- Secret Manager secret holding the Neon connection string (injected as `DATABASE_URL`)

#### Web infrastructure (`apps/web/infra/foundation/`)

The web foundation project provisions the frontend runtime, including:

- Cloud Run service for the SPA
- IAP-related configuration

#### Web domain mapping (`apps/web/infra/domain-mapping/`)

This project manages public routing for the web app, including:

- Cloud Run domain mapping
- Cloudflare DNS records

Terraform state is stored remotely in GCS, with a separate state prefix per Terraform project.

### CLI

`cli/` is a standalone Kotlin application, packaged with the **Shadow** plugin into a
fat jar and exposed through a `bin/` wrapper script (`cli/bin/workload-cli`) plus a
user-specific copy under `cli/bin/user/`, so it can be run locally without going
through Gradle directly. It is its own Gradle root, independent from
`backend/api/impl/`, and currently just prints a greeting; it exists as a starting
point for future tooling.

On push to a `trunk/*` branch, the `publish-cli.yml` workflow builds the fat jar,
publishes it as a GitHub release in a dedicated `workload-releases` repository, and
updates a Homebrew formula in a shared `homebrew-tap` repository
(`cli/tools/update-formula/`) so the CLI can be installed with `brew install`.

### Delivery model

The project is designed for automated delivery:

- GitHub Actions validate code and infrastructure changes
- Terraform workflows plan and apply infrastructure updates
- deployment workflows build containers and deploy them to Cloud Run

This makes the repository usable both as a working example and as a starting point for new internal services.

