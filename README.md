# Workload

This is an internal project built from the Counter template.

## Architecture

Workload is a small full-stack project split into a few main layers:

- **Frontend SPA** - a React/Vite application, served by Caddy
- **Backend** - one or more Kotlin gRPC services built on Armeria
- **Shared contract** - protobuf definitions used to generate client and server code
- **CLI** - a standalone Kotlin command-line tool
- **Infrastructure** - Terraform, split into a few projects by concern

At runtime, requests flow from the browser through the SPA, through sign-in, to a
backend service on Cloud Run, down to a storage layer. Production and local
development swap out the auth and storage implementations; the request-handling
logic in between stays the same.

### Layout

- `apps/web/spa/frontend/` - the browser application
- `backend/` - backend service(s) and their infrastructure
- `proto/` - the protobuf contract shared between frontend and backend
- `infra/` - shared platform infrastructure
- `cli/` - the CLI, its own Gradle root

### Frontend

A single-page app built with **React**, **Vite**, and **Mantine**, calling the
backend through generated gRPC client code. Authentication is pluggable: a
production mode backed by Google sign-in, and a local mode that skips it. The app is
packaged behind **Caddy** for static serving with SPA-style routing.

### Backend

A Kotlin server built on **Armeria**, exposing a gRPC API defined by the shared
protobuf contract. Storage and authentication are both abstracted behind
interfaces, so the same service core runs in production (backed by a managed
Postgres database, with real auth) and locally (in-memory storage, no auth)
without code changes.

### API contract

The frontend and backend share a protobuf-defined, versioned API contract under
`proto/`. Server and client code are both generated from it, so the contract is the
single source of truth for what an API looks like, and changes to it are
centralized in one place.

### Infrastructure

Managed with **Terraform**, split by concern into separate projects (shared
platform resources, backend, web foundation, web domain mapping) rather than kept
in a single root module, each with its own remote state.

### CLI

A standalone Kotlin CLI, built as its own Gradle root so it doesn't share a build
with the backend. It's packaged as a fat jar and, on push to a trunk branch,
published as a GitHub release and a Homebrew formula.

### Delivery model

The project is designed for automated delivery: GitHub Actions validate code and
infrastructure changes on pull requests, and apply/deploy them on push to a trunk
branch.
