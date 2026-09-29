# Fnord Dedup

A self-hosted, read-only file investigation application built with Groovy/Spring Boot, Angular, PostgreSQL and Docker Compose.

**Current delivery: M2 SHA-256 hashing and duplicate analysis (draft).** Scans inventory metadata, hash repeated-size regular files, and publish immutable duplicate groups. Unique-size files remain unhashed unless explicitly requested. Inspect hash attempts, reuse an accepted checksum or request a fresh read, and pause/resume/cancel durable work. Hard-link paths are distinguished from independent objects; physical savings remain unknown. Signature matching, text indexing, review plans and exports arrive in later milestones.

The application never modifies or deletes source files. Sources must be existing, explicitly configured, read-only mounts. No arbitrary-path API or source-download endpoint exists.

## First run (Linux/amd64)

Install Docker Engine with Compose v2 and Python 3, then run from the checkout:

```bash
cp deploy/.env.example deploy/.env
./scripts/setup
./scripts/preflight
./scripts/fnord up -d --build --wait
```

Open http://127.0.0.1:8088 and sign in as `operator` with the password chosen during setup. Setup generates the database password and BCrypt hash; it does not overwrite existing credentials. The default deployment starts with no sources.

Follow [OPERATIONS.md](docs/OPERATIONS.md) to configure read-only source mounts, read groups, backups and updates. Keep the default loopback binding until you deliberately configure TLS for remote access.

## Verification and next step

See [VERIFICATION.md](docs/VERIFICATION.md) for actual results and environment limitations. The Docker runtime gate remains unverified in the implementation environment. Run `./scripts/smoke-test` on a Docker-capable Linux host; it creates and removes a separate disposable test stack with generated fixtures.

`./scripts/test-all` runs the locked builds, Groovy tests, real PostgreSQL integration tests (Testcontainers), Angular tests and API type checks. It requires Java 21, Node 24.15+ in the 24.x line and Docker. No globally installed Gradle or Angular CLI is needed.

The next feature milestone is **M3 — metadata explorer, search, memos and tags**. The outstanding native PostgreSQL, process-restart and Docker acceptance gates in `VERIFICATION.md` still block deployment acceptance. M0–M2 remain drafts; any defects found by those checks must be resolved.

## Project documents

- [Specification](docs/SPECIFICATION.md) — authoritative product requirements.
- [Milestones](docs/IMPLEMENTATION_PLAN.md) and [acceptance tests](docs/ACCEPTANCE_TESTS.md).
- [Implemented API](docs/API.md) and [OpenAPI contract](docs/openapi.yaml).
- [Foundation architecture](docs/adr/0001-foundation.md) and [durable inventory design](docs/adr/0002-durable-inventory.md), and [hash evidence design](docs/adr/0003-hash-evidence.md).

The existing GNU AGPL v3 license is preserved in [LICENSE](LICENSE).
