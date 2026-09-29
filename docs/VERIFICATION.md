# M3 verification — 2026-09-29

Verified code commit: `fd94c190df65b737cae5d0e03574314758ba9616`  
Verified code tree: `9005b06ac6debd044eba6e976f0d6f7380e3817f`  
Branch: `feature/m3-explorer-annotations`  
Parent: M2 draft, `91d9916c22dfe9c89c059c3dd29fcdf5447bd8cf`

M3 implementation is ready for review, stacked on the unmerged M2 branch. It adds the stored-file explorer, combined metadata search, exact server sorting/keyset pages, location memos/tags/review states, optimistic edits and audit history, frozen bulk annotation selections, and explicit selection-based checksum requests. **Full deployment acceptance remains blocked:** independent native PostgreSQL concurrency/durability, actual process-kill recovery and Docker/read-only-mount gates are unverified. AT-52 is covered here only for frozen selection groundwork; review plans themselves remain M6. No signature matching, text indexing, byte verification, review plans or exports are claimed.

## Executed M3 checks

Final checks ran September 29, 2026, against the code above. This record is a documentation-only follow-up commit. In total, **75 tests/scenarios passed and two native-only tests were explicitly skipped**.

| Check | Result |
|---|---|
| Gradle unit/native suite | PASS: 16 tests, zero failures/skips. Linux source adapter and protection regressions retained. |
| PostgreSQL-engine integration suite | PASS in the PGlite harness: 44 passed, two native-only cases skipped. Eleven explorer/annotation/selection cases, 17 hash cases, nine inventory cases, and seven HTTP/security cases passed. Flyway V1–V4 applied. |
| Angular tests | PASS: 12 tests, zero failures/errors/skips, verified from final JUnit XML. Includes draft preservation, late-response isolation, exact decimal filters and BigInt display. |
| Angular production build | PASS: 530.86 kB initial bundle, estimated transfer 123.51 kB. |
| Locked OpenAPI generation/check | PASS: `npm run api:check`; generated TypeScript matches the staged contract. Dependency pins/locks unchanged. |
| Playwright browser suite | PASS: three scenarios in 51.4 seconds, production Angular bundle under production CSP, real Spring API and native worker on generated fixtures. |
| Production JAR | PASS: ZIP/CRC valid, 41,551,501 bytes, 329 entries. No test fixture/registry/integration classes. SHA-256 `ebd16497393875018d4fb8a1e4e8916d99df018e901f43817f43827a360984fd`. |
| Repository review | Whitespace check, unchanged specification/license/native adapter, and exact local/remote code-tree equality pass. No fixtures, credentials, dependencies or build outputs committed. |

The M3 browser scenario created a scan with **1,255 observations**, expanded saved directory nodes, navigated breadcrumbs, searched a unique-size file, saved a plain-text memo and a reusable tag containing inert HTML, opened a second tab, and demonstrated a 409 conflict without losing that tab's draft. It explicitly reloaded the saved note, froze one matching observation, applied KEEP, scheduled its checksum from the frozen selection, and observed ACCEPTED evidence. A second scan retained the same location memo, tag and KEEP state, with separate history rows. Desktop and 680-pixel-wide screenshots were inspected; the table scrolls horizontally within its panel on narrow screens, without document overflow. Existing foundation and pause/resume/disconnect/hash scenarios also passed.

Early integration failures were fixture mistakes: a newline path needed raw-byte lookup, and synthetic giant size values had to avoid observations already referenced by immutable analysis. The corrected cases pass. The first browser attempt used an invalid idempotency key containing spaces; the second reached the genuine two-tab conflict before its exact label lookup stalled at a select. The fixture key and form control labels were corrected, then all scenarios passed. Review also found and fixed cross-scan annotation-baseline invalidation, isolated late annotation responses when changing selected files, and retained the earlier hash-request idempotency format. A final visual pass improved narrow-table column widths and awaited rendered accepted evidence before capturing the screenshot.

## Commands and environment boundary

Backend checks used the committed Gradle wrapper with Java 21, through the existing local runner supplying proxy/JDK settings:

```bash
FNORD_TEST_SQL_HARNESS=pglite \
SPRING_FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK=false \
FNORD_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55439/postgres?preferQueryMode=simple' \
./gradlew test integrationTest bootJar --offline
```

Frontend checks ran from `frontend`:

```bash
npm test -- --watch=false --reporters=junit --output-file='<scratch report path>'
npm run build
npm run api:check
```

The SQL harness is PGlite 0.5.8, PostgreSQL 18.3 and pglite-socket 0.2.11. It is a single-engine test harness, not independent native PostgreSQL sessions or WAL/crash certification. Simple JDBC mode, the Flyway transactional-lock override and test-schema search paths are harness accommodations, not deployment settings. Production still targets the pinned native PostgreSQL 18.6 image. Groovy 5.0.8, Spring Boot 4.1.1, Gradle 8.14.3, Java 21, JNA 5.18.1, Angular/Material 22.0.7, TypeScript 6.0.3, Node 24.19, Vitest 4.0.18 and Playwright 1.63.0 are unchanged. The isolated OpenAPI generator remains 7.13.0 with TypeScript 5.9.3.

Browser execution used the test-only generated-fixture server and temporary same-origin proxy:

```bash
FNORD_TEST_FIXTURE_MODE=generated-only ./gradlew inventoryBrowserFixture --offline
FNORD_EXPECT_NATIVE_FIXTURES=true FNORD_TEST_URL=http://127.0.0.1:18088 \
FNORD_TEST_PASSWORD='<disposable test password>' \
FNORD_CHROMIUM_EXECUTABLE='<Chromium 153.0.8010.0 executable>' npm run e2e
```

No operator sources or host mounts were changed. Native fixture access uses the existing test-only mount-guard seam; deployed source protections remain untouched. The production JAR excludes the fixture server. This does not certify Docker read-only binds, non-root mount/permission behavior or blocked network I/O.

## M3 acceptance evidence

| Cases | Evidence and limits |
|---|---|
| AT-29 | Stored search, annotation/history and breadcrumbs continue with fixture source opening disabled. Browser lazy tree/navigation uses saved observations. Cached source status is explicitly last validation, not a live availability claim. |
| AT-30 | Combined literal name/path, extension/source/subtree/parent/type, inclusive bytes, exact nanosecond UTC half-open bounds, full SHA-256/prefix, hash/duplicate state, tags ANY/ALL, memo/review and error/stale filters. Literal `%`/`_`, newline and raw non-UTF-8 identity cases pass. Future signature/content filters reject unknown fields with 422. |
| AT-31 | Every sort in both directions traverses equal-key and null-key fixtures without duplicates/skips. Filter, annotation and evidence changes reject cursors. A global search clock also detects invalidation of an older scan used by a note baseline. New rows alone stay outside the captured cutoff. This is not a million-row performance test. |
| AT-32 | Same-location rescan preserves notes; replacement content/identity triggers the review warning; renamed location gets no inherited notes. Browser confirms persisted memo/tag/KEEP across two native scans and separate location-history observations. |
| AT-33 | Stale expected versions reject writes; browser two-tab conflict retains draft. Frozen IDs/count/snapshots remain fixed during synthetic inventory growth. Atomic conflict leaves zero batch edits; successful batches emit item/summary audit records and replay idempotently. Actor scope, tag rename, expiry, 500-row cap/no truncation and single-use behavior are covered. Independent simultaneous native PostgreSQL sessions remain unverified. |
| AT-42 | Synthetic file size `9223372036854775807`, frozen known-byte sum `18446744073709551614`, JSON decimal strings, exact size filtering and Angular BigInt unit conversion pass without allocating giant files. Unknown sizes are counted separately. |
| AT-45 | Native filenames, memos and reusable tags containing HTML render as escaped text. Browser confirms no injected image/script; unit tests preserve inert tags and warning text. Signature/content rendering awaits M4/M5. |
| AT-52 groundwork | Frozen input IDs, annotation snapshots, accepted attempts and analysis context persist. A changed tag/annotation/evidence/baseline/analysis makes the selection need review instead of changing membership. Full review-plan provenance, keeper safety and exports remain M6/M7. |
| AT-23 extension | Actor-owned frozen selection can explicitly schedule a unique-size checksum; stale/ambiguous selections reject. Existing pre-M3 hash idempotency payload format remains replayable. Browser follows a real frozen-selection hash through acceptance. |
| AT-43 regression | Authentication remains required, annotation/batch mutations require CSRF, authenticated tag create works, and invalid search input returns structured 422. |

M3 also fixes a prematurely inferred unique-size status: files remain NOT_REQUESTED while the size-candidate set is still being materialized. NOT_REQUESTED_UNIQUE_SIZE is shown only after that phase completes. A 501-file fixture covers this boundary and the frozen-selection limit.

On a Docker-capable Linux/amd64 host, run `./scripts/test-all` with harness overrides unset and `./scripts/smoke-test`; then exercise actual persistent-process kill/restart, independent sessions, nested mounts/rebinding and non-root permission cases. The two existing native-only tests remain skipped here. Million-row queries, sustained contention, backup/restore and deployment performance are unverified. Selection/audit/history retention is currently indefinite; no purge is implemented.

Work stops at the reviewable **M3 implementation milestone**. Next is **M4: the versioned signature catalog and explicit broader coverage**, after resolving any defects found by outstanding runtime gates. Nothing was merged or deployed.

---

The following records are historical and describe their earlier code commits, not the M3 implementation.

# Historical M2 verification — 2026-09-29

Verified code commit: `91dc69daac11a58daf186d101d328eda2363185a`  
Verified code tree: `a0fb6626df1f0a8487125ae8fb5763a1f60dac71`  
Branch: `feature/m2-hashing-analysis`  
Parent: M1 draft, `c61593f0581f1f9ae3c5a50f115c4c37f0120d2a`

M2 implementation is ready for review, stacked on the unmerged M1 branch. It adds repeated-size SHA-256 hashing, explicit bounded manual/forced requests, durable attempts and controls, immutable duplicate analyses, and evidence/group UI. **The full M2 exit gate is not satisfied:** native PostgreSQL concurrency/durability, real process-kill recovery and Docker/read-only-mount acceptance remain unverified. Continuing feature implementation at the user's request does not imply deployment acceptance. No signature matching, text indexing, byte verification, review plans or exports are claimed.

## Executed M2 checks

Final checks ran September 28–29, 2026, against the code above. This record is a documentation-only follow-up commit.

| Check | Result |
|---|---|
| Gradle unit/native suite | PASS: 16 tests; zero failures/skips. Existing Linux adapter and source-safety regressions included. |
| PostgreSQL-engine integration suite | PASS in the PGlite harness: 32 passed; 2 native-only cases explicitly skipped. Seventeen M2 hash/analysis cases, nine legacy inventory cases, and six HTTP/security cases passed. |
| Angular tests | PASS: 8 tests, zero failures/errors; final JUnit report confirms all four test files. Includes stale/unknown evidence display and pinned analysis pagination. |
| Angular production build | PASS: 488.21 kB initial bundle, estimated transfer 114.58 kB. |
| Locked OpenAPI generation/check | PASS: `npm run api:check`; generated TypeScript matches the staged contract. No runtime dependency pins changed. |
| Playwright browser suite | PASS: 2 scenarios, production Angular bundle, real Spring HTTP API and real native worker on generated fixtures. |
| Production JAR | PASS: ZIP/CRC valid; 41,482,113 bytes, 303 entries. Test fixture/registry classes absent. SHA-256 `c9926b03571ccbb1d2a7d7839aa3c3c4ffa1e1cdb9f00e647de870f934fc4569`. |
| Repository review | Whitespace check, generated-contract equality, unchanged specification/license, and exact local/remote code-tree equality pass. No fixtures, credentials, dependencies or build artifacts are committed. |

The browser created and paused/resumed a scan, closed its view, reconnected after completion, and browsed **1,255 committed observations**. Its fixture has two 5-byte `hello` copies and otherwise unique-size regular files. The UI showed the SHA-256 group, two independent objects and theoretical duplicate-copy bytes. The operator then explicitly requested a checksum for a 22-byte unique-size file; the real worker accepted it and the UI showed its timestamp, MANUAL provenance and attempt history. Visual inspection of progress/evidence screenshots and existing computed-style checks used the production CSP.

An early run correctly required the foundation test's migration-count assertion to change from two to three. The first browser run exposed stale test expectations for M1 copy and a mistyped digest-display prefix; those were corrected, then both scenarios passed. A final review added immediate scan-wide staleness for a detected captured root/configuration mismatch and retained controls for all unfinished manual jobs, including older paused jobs. The corresponding regression tests passed. A final explicit Angular JUnit report was used because some captured console logs ended before the summary; no partial console output is counted as a test pass.

## Commands and environment boundary

The final backend run used the repository's Gradle wrapper with Java 21:

```bash
FNORD_TEST_SQL_HARNESS=pglite \
SPRING_FLYWAY_POSTGRESQL_TRANSACTIONAL_LOCK=false \
FNORD_TEST_DB_URL='jdbc:postgresql://127.0.0.1:55439/postgres?preferQueryMode=simple' \
./gradlew test integrationTest bootJar --offline
```

Frontend checks ran in `frontend`:

```bash
npm test -- --watch=false --reporters=junit --output-file='<scratch report path>'
npm run build
npm run api:check
```

The local runner supplied build-proxy settings and the existing JDK. The SQL harness remains test-only PGlite 0.5.8, PostgreSQL 18.3 and pglite-socket 0.2.11. Simple JDBC mode, Flyway transactional-lock override and explicit test-schema initialization address that harness's single-engine limitations; they are not deployment settings. Production targets the pinned native PostgreSQL 18.6 image. Groovy 5.0.8, Spring Boot 4.1.1, Gradle 8.14.3, JNA 5.18.1, Angular/Material 22.0.7 and the committed dependency locks are unchanged.

Browser execution used the test-only generated fixture server and temporary same-origin frontend proxy under the production CSP:

```bash
FNORD_TEST_FIXTURE_MODE=generated-only ./gradlew inventoryBrowserFixture --offline
FNORD_EXPECT_NATIVE_FIXTURES=true FNORD_TEST_URL=http://127.0.0.1:18088 \
FNORD_TEST_PASSWORD='<disposable test password>' \
FNORD_CHROMIUM_EXECUTABLE='<Chromium 153.0.8010.0 executable>' npm run e2e
```

Generated fixtures use a test-only mount-guard/registry seam; actual metadata, raw-byte opening and reads use the Linux native adapter. Mutation and corrupted-read tests concern only disposable fixture data. No production source-writing capability or mount-validation bypass was added. These tests do not prove Docker bind-mount protection, independent PostgreSQL sessions, WAL durability or crash recovery on a persistent service.

## M2 acceptance evidence

| Cases | Evidence and limits |
|---|---|
| AT-19 | Repeated-size selection excludes unique-size files and a sole empty file. A 503-empty-file fixture crosses candidate-selection batches without duplicate tasks. Legacy inventory tests still reject every content open. |
| AT-20–22 | Exact known `hello`/empty SHA-256, complete bytes/times/fingerprints and 32-byte storage; same-size unequal content stays separate; renamed, empty and cross-root copies group normally. |
| AT-23 | Unique-size manual job, idempotent replay/conflict, retained accepted timestamp on reuse and zero extra reads. Mixed-scan, duplicate, oversized and arbitrary-path selections are rejected. Browser proves manual unique-size hashing end to end. |
| AT-24 | Pause at the first and third 1 MiB boundaries, discard partial digests, restart at zero, preserve useful counts and count physical rereads. Mid-read cancellation remains terminal. Actual blocked-network-I/O and forced process-kill timing remain unverified. |
| AT-25 | Generated files modified, truncated, grown, replaced or removed during native reads never accept a wrong-observation digest. Existing adapter tests cover pre-open replacement/fingerprint rejection. Original observation sizes remain unchanged. |
| AT-26 | Injected forced digest disagreement invalidates the active pointer and old published group immediately; the conflicting attempt stores no digest. Rebuild excludes that observation while preserving old attempts/revisions. Captured configuration changes invalidate the scan evidence window without a tree-wide rewrite. |
| AT-27 | Second scan independently rereads repeated-size paths; no cross-scan accepted cache or duplicate-copy counting. A unique-size manual hash in the first scan is absent in the second. |
| AT-28 | Paused analysis remains unpublished. A manual job completes while the earlier build is paused; resuming abandons its outdated capture and publishes a consistent revision. A 503-member group pauses after 500 member assignments and publishes only after the remaining batch. These are deterministic interleavings, not certification of independent native sessions or process death during publication. |
| AT-18 | Expired hash claims cannot commit; interrupted attempts and explicit resume are covered. Existing sequential inventory fences/rollback pass. Two native-only concurrency/connection-loss cases remain skipped. |
| M2 object accounting | Hard-link paths are independently hashed; a hard-link-only group reports U=1 and zero duplicate-copy bytes. Unknown filesystem identity yields null object counts/estimates. Real bind aliases, allocation/extents and full M6 planning gates remain unverified/not implemented. |

The outstanding runtime gates remain blockers for deployment acceptance. On a Docker-capable Linux/amd64 host, run `./scripts/test-all` with harness overrides unset, then `./scripts/smoke-test`. Execute process-kill/restart against persistent native PostgreSQL, actual nested-mount/rebinding and non-root permission cases, plus the M0/M1 deployment checks below. No throughput, million-row scale or physical-reclamation benchmark is claimed.

Work stops at this reviewable **M2 implementation milestone**. The next feature milestone is **M3: metadata explorer/search, memos and tags**, after resolving any defects found by the outstanding gates. Nothing was merged or deployed.

---

The following records are historical and describe the earlier M1/M0 code, not M2 capabilities.

# Historical M1 verification — 2026-09-28

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
