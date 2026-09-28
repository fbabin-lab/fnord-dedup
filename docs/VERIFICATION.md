# M1 verification — 2026-09-28

Verified code commit: `63a3c2d7eeafefcea6dc7e50165b11751bdefe89`  
Verified code tree: `8be056fbbe16edf4e6c15a0558a6b3e018ac1c17`  
Branch: `feature/m1-durable-inventory`  
Parent: M0 draft, `31f200d9e5313e8866a08b10d29a5c3c7215a2d7`

M1 implementation is ready for review as a separate draft stacked on M0. It adds metadata inventory, durable controls, immutable observations, per-source/directory coverage and stored-result browsing. **The full M1 exit gate is not satisfied.** Docker, native PostgreSQL concurrency/durability, actual backend kill/restart and real mount-change checks remain unverified. Development continued at the user's request; neither M0 nor M1 is being declared deployment-accepted. No hashing, duplicate analysis, signature matching, text indexing, plans or exports are claimed.

## Executed M1 checks

| Check | Result |
|---|---|
| Gradle unit/native tests | PASS: 16 tests, no failures/skips. Includes the existing source-safety regression suite. |
| SQL/application integration suite | PASS in the stated PGlite harness: 14 passed; 2 native-PostgreSQL-only cases explicitly skipped. Five HTTP/security cases and nine inventory/database/native fixture cases passed. |
| Angular `npm test` | PASS: 6 tests, including exact large values, inert untrusted names, no fake percentage, polling teardown without server cancellation, and exact integer rates. |
| Angular production `npm run build` | PASS: 475.48 kB initial bundle; estimated transfer 111.76 kB. |
| Locked generator `npm run api:check` | PASS: generated Angular types match the staged OpenAPI contract; generator npm install uses its lockfile. No runtime dependency pins changed. |
| Playwright `npm run e2e` | PASS: 2 scenarios against the production Angular bundle, real Spring HTTP API and real native fixture worker. |
| Production JAR | PASS after a fresh `bootJar --offline --rerun-tasks`: valid ZIP/CRC, 41,416,381 bytes, 279 entries. Test fixture/registry classes are absent. SHA-256 `cea0afb1d6e45d2a356882cd470f18bc9f9be12336bc9e26fd28fcae49e18d8a`. |
| Review checks | Python/Bash helper parsing, diff whitespace, unchanged license/specification and exact local/remote Git-tree equality pass. No generated source fixtures, credentials, dependencies, screenshots or build artifacts are committed. |

The browser exercised a generated tree with **1,255 observations**: 1,251 regular files, three directories including the root, and one symlink. It created a scan through the form, paused and resumed it, closed the view, reconnected after server completion, and browsed paged database results/file metadata. A separate native integration fixture covered two roots, empty/hidden paths, composed/decomposed Unicode, invalid UTF-8 bytes, control characters, internal/external/loop symlinks and a FIFO. A guarded adapter throws if inventory attempts to open file contents.

Visual inspection and computed-style assertions ran under the production CSP. They exposed Angular's critical-CSS loader depending on an inline event handler blocked by `script-src self`; production builds now use a normal stylesheet link without weakening the policy. The browser also caught and verified fixes for explicit Groovy request-parameter bindings and Angular form submission.

## Commands and harness boundaries

The environment still has no Docker daemon or usable native PostgreSQL service. It used the same test-only PGlite 0.5.8 PostgreSQL 18.3 engine and `@electric-sql/pglite-socket` 0.2.11 as M0. Integration execution was equivalent to:

```bash
FNORD_TEST_SQL_HARNESS=pglite \
SPRING_FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK=false \
FNORD_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55439/postgres?preferQueryMode=simple' \
./gradlew test integrationTest bootJar --offline

./gradlew bootJar --offline --rerun-tasks
npm test
npm run build
npm run api:check
```

The local runner provided Java 21 and build-proxy configuration. Frontend commands ran in `frontend`; Gradle commands ran in `backend`. A first artifact inspection found a truncated local JAR; the fresh packaging run above rebuilt and validated the complete archive. This did not change application source.

The harness multiplexes sessions into one PostgreSQL engine. Simple JDBC query mode avoids its extended-protocol/prepared-statement error handling limitations; disabling Flyway's transactional lock avoids its single-engine lock limitation. The test pool explicitly initializes its schema. These are test-harness accommodations, not deployment settings. The production schema, transaction logic and locking requirements remain PostgreSQL-native.

The two skipped tests require genuinely separate PostgreSQL sessions/processes:

- Competing scheduler acquisition and completion/cancel transactions under concurrency.
- Terminating the test's own database connection during a result transaction, proving rollback and recovery after actual connection loss.

Sequential lease takeover, expiry, late-token rejection, failed-batch rollback, idempotent replay and job transitions passed in the harness. **Those passes do not certify native lock contention, independent sessions, WAL durability, or recovery after an actual database outage.**

For the browser, the test-only `inventoryBrowserFixture` Gradle task started the real Spring app with a generated native fixture registry. The worker, database store and controllers were the production implementations; fixture source reads used the real Linux adapter. Only mount validation/source-registry validation was replaced for those newly generated writable fixtures. The test app is absent from the production JAR and no production flag disables read-only guards. The built Angular bundle was served by a temporary same-origin proxy using the production CSP. Chromium 153.0.8010.0 ran:

```bash
FNORD_TEST_FIXTURE_MODE=generated-only ./gradlew inventoryBrowserFixture --offline
# Against that disposable server and its same-origin frontend:
FNORD_EXPECT_NATIVE_FIXTURES=true FNORD_TEST_URL=http://127.0.0.1:18088 \
FNORD_TEST_PASSWORD='<disposable test password>' \
FNORD_CHROMIUM_EXECUTABLE='<local Chromium executable>' npm run e2e
```

The browser proof is of real native inventory and HTTP/UI integration, not real read-only Docker mounts or Nginx/container operation.

## Acceptance mapping and remaining gate

| Cases | M1 evidence and limits |
|---|---|
| AT-10 / AT-06–07 | Real recursive native fixture observations across two roots; hidden/empty/unusual/raw-byte paths, symlinks and FIFO; zero content opens. Browser fixture exercises multiple inventory batches. Actual deployed mount behavior pending. |
| AT-11 | Stored seconds/nanoseconds agree with native statx; existing one-nanosecond fingerprint regression passes. |
| AT-12 | Injected metadata/list/root failures preserve other work and prior scans; partial/unavailable coverage propagates correctly. Real non-root permission fixtures pending. |
| AT-13–14 | Pause/lease takeover after committed and final batches, replay uniqueness, retained original metadata, appended instability and exact counters pass. Actual forced-process-loss testing pending. |
| AT-15 | Idempotent controls and sequential cancel/pause/recovery transitions pass; late completion/cancel serialization under true concurrency is explicitly unverified. |
| AT-16 | Browser close/reconnect leaves server work running. Database-state recovery simulation passes; actual backend kill/restart on persistent PostgreSQL pending. |
| AT-17 | Configuration revision, recorded identity and unavailable/writable-status resume blocks pass with test injection. Actual remount/replacement/included writable submount cases pending. |
| AT-18 | Sequential lease expiry, second-owner rejection, fencing and transactional rollback pass in the SQL harness. Native concurrency/connection-loss tests are supplied but skipped here. |
| AT-29 / AT-31 / AT-42 / AT-43 / AT-45 | Stored-only navigation, committed cutoff pagination, exact decimal presentation, authenticated/CSRF-protected inventory routes and escaped names have focused M1 coverage. Full M3 query/annotation acceptance remains later work. |

On a Docker-capable Linux/amd64 host, run `./scripts/test-all` with `FNORD_TEST_SQL_HARNESS` unset so the native cases execute, then `./scripts/smoke-test`. The smoke browser scenario now creates a recursive inventory through the actual read-only mounted root and checks persisted browsing, in addition to its M0 protections and fixture before/after comparison. Neither command's Docker-dependent gate ran here.

Also execute the explicit process-kill/restart, real nested-mount changes, runtime UID/group access and the other M0 deployment checks below. The next feature milestone is **M2: repeated-size SHA-256 candidate hashing and duplicate analysis**, after the outstanding runtime gates and any resulting defects are resolved. Work stops at this reviewable M1 implementation boundary; nothing was merged or deployed.

---

The following record describes the earlier M0 commit; its narrower feature claims are historical.

# Historical M0 verification — 2026-09-28

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

At the M0 handoff, M1 was the next feature milestone. Its outstanding Docker acceptance gate remains open and is carried into the current draft above. Source safety defects remain blockers; unavailable environment checks are not reported as passes.
