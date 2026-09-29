# Operations — M3 explorer and location annotations

This milestone inventories metadata, hashes repeated-size regular files with SHA-256, and publishes duplicate analysis through the Linux read-only adapter. It stores observations, attempts, revisions and job controls in PostgreSQL. Unique-size hashing requires an explicit manual request. Historical M1 scans remain inventory-only. There is no browser command for opening an arbitrary file.

## Requirements and initial startup

Use Linux/amd64, Java 21 for local development, Docker Engine with Compose v2, and Python 3 for helper scripts. A Linux kernel supporting `openat2` and statx mount IDs is required (Linux 5.8+; current supported Ubuntu kernels are preferable). Unsupported confinement/metadata operations fail closed. Backend/frontend run as non-root, with read-only container root filesystems and dropped capabilities.

From the repository root:

```bash
cp deploy/.env.example deploy/.env
./scripts/setup
./scripts/preflight
./scripts/fnord up -d --build --wait
./scripts/fnord ps
```

`setup` prompts for a password, builds the backend, hashes the password in an isolated short-lived container without a source mount, and generates a random database credential. It writes only `deploy/secrets`. It refuses to overwrite existing credentials. The secret directory has mode 0700; secret files have mode 0444 so the configured non-root container users can read the individual mounted files. Host users cannot traverse the private containing directory. Do not relax that directory's permissions.

The operator defaults to `operator`; change `FNORD_OPERATOR_USERNAME` before first startup if desired. Credentials, `.env`, private source config and overrides are git-ignored. No default password or public registration exists. BCrypt cost is 12 by default; accepted configured costs are 12–16. Missing, plaintext, low-cost and known placeholder operator credentials are rejected. Database secrets must have at least 24 characters.

Only the frontend is published, normally on `127.0.0.1:8088`. PostgreSQL and the backend have no host ports. The DB network is separate and internal. Use `COMPOSE_PROJECT_NAME` and `FNORD_PORT` in `deploy/.env` to run more than one stack. No global container/network names are imposed.

## Add sources

```bash
cp deploy/compose.sources.example.yaml deploy/compose.sources.yaml
cp deploy/application.example.yml deploy/application.yml
```

Edit `deploy/.env` to set `FNORD_SOURCE_ARCHIVE` to an existing absolute host directory. Keep the bind's `read_only: true`, `create_host_path: false`, and private propagation. In `application.yml`, give each source a stable UUID and a stable backing-dataset `source-instance-id`. Generate new UUIDs with `python3 -c 'import uuid; print(uuid.uuid4())'`. Preserve both across normal restarts; assign a new source instance when replacing the backing dataset. Do not reuse the example identities for unrelated datasets.

Repeat **both** the bind declaration and registry entry for additional sources. The UI cannot create mounts. The optional host-export prefix is an operator-supplied mapping for later export milestones; M3 performs no exports. An invalid/missing path is not automatically created. Source configuration is snapshotted in PostgreSQL by SHA-256 configuration revision; this hash is application configuration metadata, not a source-file hash.

```bash
./scripts/preflight
./scripts/fnord up -d --build --wait
```

If only the contents of `application.yml` changed and Compose did not recreate the container, run `./scripts/fnord restart backend` and wait for health. Source status is refreshed at backend startup and explicit job start/resume validation. The API reads cached validation; refreshing the Sources page does not trigger a filesystem scan.

The runtime checks the opened mount's read-only flag **and** the actual container mount table. Writable roots are blocked. `cross-mounts: false` excludes nested mount boundaries; explicit `true` requires all included mounts to be read-only. Ordinary nested roots and backing-directory bind aliases are flagged as overlapping. Application-owned storage must not appear within the included source data. There is no write probe in source validation/preflight.

### UID/GID and native library loading

The backend runs as UID/GID 10001. The operator must grant that UID, an appropriate read group, or other read-only ACL sufficient directory search/list and file-read access. The application never changes source permissions. If an existing host group grants read access, add its numeric GID under `services.backend.group_add` in the private override:

```yaml
services:
  backend:
    group_add: ['1234']
```

Preflight can inspect access only as the current host user; the backend's startup validation is authoritative for its runtime credentials. Do not use privileged mode or mount the Docker socket. Source symlinks, including root path components, are never followed. JNA extracts its pinned bundled native library into the application-owned `/tmp` tmpfs; this location must support library mappings. No native library is loaded from source directories.

Read-only mounts prevent this application from writing through those mounts, but host processes may still change the files. The adapter compares exact metadata and the directory entry around reads; this is not a point-in-time snapshot or protection against an adversary who restores bytes/metadata between checks.

## Health, logs and resources

```bash
./scripts/fnord ps
./scripts/fnord logs --tail=100 backend
curl --fail http://127.0.0.1:8088/api/v1/system/health
```

The backend emits structured JSON logs and correlation IDs. Source contents and operator passwords are not logged by application code. Nginx access logs are disabled. Compose rotates logs at three 10 MiB files per service. Backend/frontend memory and PID limits, read-only roots, tmpfs and graceful shutdown are configured. PostgreSQL persists on its own named volume mounted at the image's PostgreSQL-18 parent data directory, `/var/lib/postgresql`. Application artifacts have a separate backend-only named volume.

Source unavailability degrades that source's status; it does not erase configuration history or make the health endpoint fail. Database unavailability returns DOWN and prevents normal application initialization/work. Actuator metrics infrastructure is present through Boot, but only health is exposed. Job progress comes from persisted counters and heartbeat/checkpoint timestamps; committed state events include the job ID in structured logs.

## Stop, restart and passwords

`./scripts/fnord stop` stops services while preserving their volumes. `./scripts/fnord up -d --wait` starts them again. Backend restart invalidates in-memory sessions, so sign in again. Active work closes handles at a safe boundary when I/O permits. On restart, recovery changes active jobs to INTERRUPTED (or CANCELLED if cancellation was already requested), and you must explicitly resume interrupted work. A previous process's 60-second database lease may need to expire before recovery. Queued jobs that had not started remain queued. Do not run `down --volumes` on an operator stack unless you intend to delete its database/artifact history.

To change only the operator password, generate a new hash with the same backend image's `--hash-password-stdin` command using a local program that passes the password over stdin, replace `deploy/secrets/operator-password-hash`, and recreate the backend. Never put the plaintext password in shell arguments or commit it. `setup` intentionally refuses password rotation. Database credential rotation requires changing the PostgreSQL role password and the mounted secret together; merely changing `POSTGRES_PASSWORD_FILE` does not update an initialized database.

## Backup, restore and upgrades

Protect database dumps, credentials, configuration and artifact backups as sensitive data. Use host/storage encryption and protected backups as appropriate. For a consistent maintenance backup, stop frontend/backend, keep PostgreSQL running, and run from the repository root:

```bash
umask 077
./scripts/fnord stop frontend backend
./scripts/fnord exec -T postgres pg_dump -U fnord -d fnord -Fc > fnord-backup.dump
./scripts/fnord up -d --wait
```

M3 has no export artifacts. Later milestones must back up the artifact volume consistently with the database. Preserve private source configuration and identities separately from the dump. Restore only into a new isolated stack/database: after initializing it, feed the dump to `pg_restore -U fnord -d fnord --clean --if-exists` via `scripts/fnord exec -T postgres`. Ensure the isolated stack has its own project name, ports and volumes; do not point it at live writable sources. Backup/restore is documented but not exercised by the implementation environment.

Images and application dependencies are pinned. For updates, back up first, review migrations, build the new revision, then recreate services with `up -d --build --wait`. Flyway migrations are forward-only. A code rollback may require restoring a matching pre-upgrade database; do not modify an applied migration or reuse a PostgreSQL data volume across incompatible major versions.

## Remote exposure

Keep the loopback default for local use. For deliberate remote access, terminate HTTPS at a trusted reverse proxy, set `FNORD_SECURE_COOKIES=true`, and enforce access restrictions appropriate to the deployment. Both session and CSRF cookies then use Secure. Forwarded headers are not trusted for authentication/throttling. No permissive credentialed CORS or external analytics/fonts are used. HTTPS deployment has not been validated in this milestone.

## Tests

`scripts/test-all` requires Java 21, Node 24 and Docker and runs the build/unit/real-PostgreSQL integration gate. `scripts/smoke-test` creates a uniquely named disposable Compose project, generates benign fixtures and temporary credentials, verifies HTTP authentication/CSRF, mount statuses, recursive inventory and database-backed browsing, attempts a write **only inside its disposable fixture**, checks fixture metadata/content afterward, and removes only its own volumes. The browser test requires Chromium dependencies; on a development host install them with `cd frontend && npx playwright install --with-deps chromium` if necessary. No operator configuration or source mount is used by the smoke test.

Actual nested host-mount integration testing requires an explicitly prepared disposable environment. Native unit/inventory tests exercise real Linux native operations on generated writable fixtures using a test-only mount-guard seam, plus separate production writable-root rejection. This seam has no application configuration flag and is not used by the deployed application. It does not substitute for the Docker read-only mount gate.


## Inventory and job controls

Select **Scans**, enter a name, and choose available registered sources. The server admits at most ten unfinished jobs and five new scan/hash jobs per operator per minute. Sources include hidden files and empty directories. Symlinks are metadata only; special files are never opened for contents. The default excludes nested mount boundaries and reports them in coverage.

The detail page polls durable progress every two seconds while active. It shows observed counts and exact decimal byte totals; discovery has no known total or percentage. Open a source to browse committed rows. Refresh entries to capture a new page cutoff while a scan is growing. Closing a page stops polling, not the worker.

Pause/cancel are requests until the worker commits a bounded batch and closes its source handles. Under healthy I/O the worker checks between batches of at most 500 entries or about one second of enumeration. A syscall blocked on a network filesystem can delay acknowledgement. “Waiting for I/O or a checkpoint” means five seconds without a checkpoint, not a promise of immediate interruption. Never forcibly terminate a worker thread.

Resume validates the captured configuration revision, availability, read-only policy and mounted root identity. Restore the captured configuration or start a new scan when a source instance/binding changed. Original observations remain historical. An unfinished directory replays from its beginning; already committed paths are not duplicated or overwritten. Conflicting metadata marks the original observation unstable. No unvisited subtree is interpreted as deleted history.

A single database coordination lease permits one scheduler. It lasts 60 seconds and renews every 10 seconds. Work claims have independent monotonically increasing tokens. Every result transaction checks scheduler ownership and the work lease under row locks. An expired work claim interrupts its job and requires explicit resume. Running more than one backend is not a scaling mode.

For test-only native browser fixtures, point `FNORD_DB_URL` at a **disposable** database, supply the normal test credentials, set `FNORD_TEST_FIXTURE_MODE=generated-only`, and run `./gradlew inventoryBrowserFixture` from `backend`. This task uses the test classpath and generates its own temporary files. It bypasses mount validation for those generated fixtures only; the production JAR contains neither that fixture server nor its override. Use `FNORD_EXPECT_NATIVE_FIXTURES=true` with the Playwright suite against that server. This does not replace the Docker mount gate.

The integration suite uses its own `inventory_test`, `hash_test` and `explorer_test` schemas. `FNORD_TEST_DB_URL`, when supplied instead of Testcontainers, must reference a disposable test database: tests clear fixture tables, and native tests terminate only the PostgreSQL connection they created to exercise rollback/recovery. Never point the suite at application history or an operator database.


## Hashing and duplicate analysis

After inventory freezes, the server materializes repeated sizes and hash tasks in batches. Each path is read independently, including hard links. Hashing uses a 1 MiB buffer and checks pause/cancel between reads; handles close before the stop is acknowledged. Resuming rereads unfinished files from byte zero. Completed attempts and published analyses remain available if later work is cancelled.

Open an observation to **Calculate checksum**, including a unique-size file. An accepted result is reused with its original time. **Force fresh checksum** requires a separate confirmation and performs a new read. A conflicting digest or changed observation invalidates dependent evidence; start a new scan to obtain new observations. A detected captured configuration/root identity mismatch invalidates the scan's evidence window conservatively, even if the configuration is later restored. Original inventory and attempts remain historical.

Duplicate groups are published only after all capture/build/member batches finish. Group pages pin that immutable revision, and show `STALE` immediately when captured evidence is invalidated. A later manual job publishes a new analysis. Abandoned/interrupted builds are retained for investigation and are never current. M3 does not purge history automatically; monitor PostgreSQL growth.

Object counts are conservative. Unknown identity and repeated mount views produce no exact object-level estimate. The displayed duplicate-copy byte measure is theoretical; actual physical savings remain unknown. Byte comparison and review plans belong to M6. Signature candidates and text indexing are still rejected when enabled; those features remain M4/M5 requirements.


## Explorer, notes and frozen review

From a scan, choose **Open file explorer and notes**. The directory tree loads only saved directory pages. Breadcrumbs and table rows remain available when a source is disconnected. Source status is explicitly the last startup/job validation, not a live availability promise. Scan progress polls every ten seconds here without reloading note drafts; use **Refresh results** to capture new rows or annotation changes.

Metadata filters combine with AND. Filename/path contains and exact filters compare literal, case-sensitive UTF-8 bytes; `%` and `_` are data. Use base64 exact-name/path filters for non-UTF-8 names. Extension values use the stored display extension, case-sensitive. Date bounds are UTC ISO instants ending in `Z`; the lower bound is inclusive and upper exclusive, including nanoseconds. Size inputs and displayed byte totals are decimal strings. Sorting happens on the server by raw name/path bytes, exact size or mtime, or checksum completion time, with UUID ties and nulls last. A stale cursor asks you to refresh rather than silently skipping changed rows.

**Location annotations** apply to one raw path in one source instance. Enter a plain-text memo (20,000 Unicode characters), up to 100 reusable tags, and a review state. Tags trim Unicode whitespace and compare by NFKC followed by lowercase using Locale.ROOT; displayed spelling is retained. This normalization never applies to file identity. Editing a shared tag label changes its display at all associated locations.

Notes persist across rescans. A differing file fingerprint, digest or invalidated baseline shows **File changed; review existing annotations.** Saving location notes explicitly reaffirms their baseline for the displayed observation. Renamed locations start without notes. `KEEP` is a stored preference for future protected review plans; `REMOVAL_REVIEW` performs no file action. All note/tag changes retain audit records. Memos and captured snapshots are application data; include them in database backup/privacy planning.

When two views edit the same notes, a stale save returns a conflict and leaves the draft visible. **Discard draft and reload** explicitly replaces it with the latest stored value. Regular progress polling does not discard edits.

**Select this page** chooses the visible rows; **Freeze all matching results** resolves every matching row in the captured view, up to 500. Larger sets are rejected without truncation: refine filters or select fewer rows. Review the frozen count, known logical bytes, unknown-size count and member preview before applying tags/review state. Targets never expand with new inventory rows. Member annotation/tag changes, accepted-evidence changes or a new analysis invalidate the review. The whole batch rolls back on conflict. Bulk tag/state updates preserve the existing memo baseline, including warnings about replacement content.

Selections belong to the operator, expire after 24 hours, and remain in history. Each annotation selection applies once; request retries use the same idempotency key. **Calculate frozen checksums** explicitly authorizes reads for that frozen selection of eligible regular files, including unique-size observations. Follow the returned job from the scan progress view for pause/resume/cancel. Tagging and query operations never open source files.

M3 adds forward-only Flyway migration V4. It does not modify V1–V3 or the source registry. The new views query saved PostgreSQL records; the application-level selection limit is 500, tree display limit is 1,000 directories, and breadcrumb limit is 256 ancestors. A single short annotation-clock lock serializes metadata edits; million-row query scale and independent native PostgreSQL concurrency remain acceptance gates. No automatic selection/audit/history purge is implemented; monitor database growth.
