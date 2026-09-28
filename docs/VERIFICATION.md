# M0 verification — 2026-09-28

Code commit: `3d02408ab38119f331bbeb86c9e456ff230e034e`  
Source tree: `907ece12dde6d472d59d072704a2ffc39ab3b0af`  
Branch: `feature/m0-foundation`  
Base: `e3ef41764fc267402dd719125f512ac6ec392568`

M0 implementation is delivered for review. **The full M0 exit gate is not yet satisfied:** this environment has no Docker daemon and cannot perform the required real-container/read-only-mount checks. The PR remains a draft. No M1 scan/job functionality is claimed.

## Executed checks

| Check | Actual result |
|---|---|
| Gradle Wrapper 8.14.3 distribution download/checksum and Java 21 build | PASS; wrapper successfully launches the pinned build. |
| `./gradlew test bootJar --offline` | PASS; 16 Groovy unit/native tests, 0 failures, 0 skipped; executable JAR built. |
| `./gradlew test integrationTest bootJar --offline` against the local SQL harness below | PASS; the 16 unit/native tests plus 4 application/database/security integration tests, 0 failures, 0 skipped. |
| `npm ci --no-audit --no-fund` in frontend | PASS from the committed lockfile. |
| `npm run api:generate` / `npm run api:check` | PASS with the separately locked OpenAPI generator; generated Angular schema matches the contract. |
| `npm test` in frontend | PASS; 3 tests (session refresh and safe source rendering/empty state). |
| `npm run build` in frontend | PASS; production initial bundle 441.69 kB, estimated transfer 102.97 kB. |
| `npm run e2e` with Chromium 153.0.8010.0 | PASS; 1 browser scenario against the production Angular bundle and live Spring Boot JAR. |
| `docker-compose -f deploy/compose.yaml config --quiet` using standalone Compose 2.40.3 | PASS; schema resolves. Additional assertions verified loopback frontend-only publishing, private backend/DB and backend/frontend read-only roots/capability drops. |
| Helper scripts | Python AST parsing and `bash -n` pass. Docker-dependent execution remains unrun. |
| Repository checks | Original license and specification bytes unchanged; staged diff whitespace check passes; executable script/wrapper modes retained; no credentials, generated source fixtures, dependencies or build outputs included. |

The local Gradle runner supplied Java 21 and the execution environment's temporary build-proxy settings; those machine-specific values are not part of the repository. The wrapper distribution SHA-256 is `bd71102213493060956ec229d946beee57158dbd89d0e62b91bca0fa2c5f3531`. The wrapper JAR SHA-256 is `7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172`.

## Native evidence

Real Linux/amd64 JNA calls tested on disposable generated fixtures:

- Exact enumeration/opening of spaces, quotes, comma, tab, CR/LF, leading hyphen, backslash, composed/decomposed Unicode and invalid UTF-8 bytes; escaped display strings remain distinct from identity bytes.
- Rejection of inside/outside symlink content opens, symlink directory loops, absolute/parent/dot/empty components and a FIFO replacement without blocking.
- Detection of changed/replaced file paths, replaced directory entries during enumeration, incomplete reads and nanosecond fingerprint differences.
- Component-based opening of a path longer than 4,096 bytes and complete validation of an empty file.
- Streaming a 512 MiB sparse regular file with a 64 KiB buffer under a 256 MiB test heap; successful EOF/size/fingerprint validation. No throughput/peak-memory benchmark is claimed.
- Root closure releases children; repeated successful and failing operations do not exhaust the adapter's 64-handle cap.
- Production mount guard rejects an actually writable fixture without changing content, mode, inode, size, mtime or ctime.
- Mount-table parsing, included/excluded nested-mount policy and backing bind-path relationships; actual kernel mountinfo is parsed. Synthetic submount policy tests do not certify real nested mounts.
- A source with an unavailable mount ID does not prevent unrelated source statuses from being returned.

Native reading fixtures use a package-private test seam that replaces **only** mount validation, because these generated fixtures reside on writable scratch storage. Source operations themselves use the real native adapter. There is no production configuration switch to disable the guard. This evidence must not be described as a real Docker read-only-mount test.

## Application/database/browser harness limits

Native PostgreSQL and Testcontainers could not run: Docker is absent, and this restricted environment cannot run a native PostgreSQL service under its required non-root account. For additional application verification, a disposable PGlite 0.5.8 PostgreSQL **18.3** engine with `@electric-sql/pglite-socket` 0.2.11 provided a local JDBC protocol server. This was a test-only harness, not an application dependency or database replacement.

The application tests used:

```bash
SPRING_FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK=false \
FNORD_TEST_DB_URL=jdbc:postgresql://127.0.0.1:55439/postgres \
./gradlew test integrationTest bootJar --offline
```

The transaction-lock override was needed only for the single-engine multiplexed test harness. It is **not** configured in deployment. The tests exercised real Flyway SQL, configuration persistence, login/logout audit rows, authentication, CSRF, absent unsafe routes, restrictive CORS behavior and global login rate limiting. They do not certify native PostgreSQL password enforcement, process concurrency, durability, locking, WAL or container startup.

The browser used the built Angular bundle, a temporary same-origin static/API proxy with the production CSP, and the real Spring JAR connected to that harness. It verified anonymous rejection, login, authenticated navigation, the explicit unavailable-scanning message, HttpOnly/SameSite cookies, missing-CSRF rejection and logout. It did **not** execute Nginx or the Docker images. The standard Playwright browser download returned unusable archives in this environment; an available local Chromium 153.0.8010.0 executable ran the test successfully.

## Required Docker-host follow-up

On a Docker-capable Linux/amd64 development host:

```bash
./scripts/test-all
./scripts/smoke-test
```

`test-all` defaults to Testcontainers with the pinned PostgreSQL 18.6 image when `FNORD_TEST_DB_URL` is unset. Do not carry the test-harness Flyway override into this run. `smoke-test` builds the real images, starts a uniquely named disposable Compose stack with generated read-only and writable roots, checks the actual mounted source statuses, checks that an attempted write to its disposable read-only fixture fails with EROFS, runs the browser workflow, verifies unchanged source metadata/content, and removes its own test volumes. It does not use operator sources.

Also validate explicitly prepared disposable nested mounts (`cross-mounts` false/true), non-root UID/supplementary-group access, JNA loading under the runtime tmpfs/security settings, HTTPS Secure cookies, and fresh-checkout setup. Native PostgreSQL backup/restore is documented but untested. No source mount was changed on the user's host and no deployment was performed.

## Acceptance mapping and next milestone

| Acceptance cases | Current evidence |
|---|---|
| AT-01 | Locked builds, migration and browser/backend communication pass in the stated local harness; actual Compose startup pending. |
| AT-02 / AT-44 | Production source interface/API inspection confirms no mutation/download/execution routes. Login throttling/placeholder rejection tested. Full scan/index/plan/export cycle and job-creation limits belong to later milestones. |
| AT-03 | Real writable-root rejection passes. Docker read-only negative write test supplied but unrun. |
| AT-04 | Mount parsing and policy tests pass; actual nested-mount integration pending. |
| AT-05–AT-09 | Adapter cases listed above pass. Full persisted inventory/hash behavior is not yet implemented. Socket/device fixtures and adversarial concurrent mount changes remain unverified. |
| AT-43 | HTTP-local authentication, CSRF, session cookies and no credentialed wildcard CORS pass. HTTPS deployment remains unverified. |
| Other v1 cases | Not delivered or claimed by M0. Follow the original M1–M8 plan. |

Close the outstanding M0 environment gates before advancing to **M1: durable recursive inventory, immutable observations/source snapshots, PostgreSQL job queues/leases, progress, pause/resume/cancel and restart recovery**. Source safety defects must be resolved before scans are enabled.
