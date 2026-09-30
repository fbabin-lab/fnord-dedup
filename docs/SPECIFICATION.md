# Fnord Dedup — AI implementation specification

**Version:** 1.0  
**Date:** September 28, 2026  
**Target repository:** `fbabin-lab/fnord-dedup`  
**Status:** Specification for implementation; this document does not claim that the application exists.  
**Primary invariant:** Identify and report. Never modify or delete scanned source files.  
**Language:** English UI, API messages, and documentation only. No internationalization framework.

## 1. Purpose and binding decisions

Build a self-hosted application that inventories mounted directories, detects duplicate file content, matches files against a user-maintained signature catalog, supports investigation through a web UI, and produces review/export lists for separate external tools.

“Sensitive,” “unwanted,” and “banned” are user-supplied classifications. The application must not infer legality, inspect images with AI, obtain external blacklists, upload files, or claim to find every prohibited file. A catalog match means that a file matches a signature the operator supplied, not that the software has made a legal determination.

The words **MUST**, **MUST NOT**, and **SHOULD** are normative. Where an implementation choice is not specified, choose the smallest maintainable solution consistent with the safety and correctness requirements. Do not substitute another backend language, framework, database, or frontend stack.

### 1.1 Product decisions resolving ambiguities

| Topic | Required decision |
|---|---|
| Backend | Groovy application code, Spring Boot REST web service. “Sprint Boot” in the request is interpreted as Spring Boot. |
| Frontend | Angular and TypeScript; a browser UI, not a desktop application. |
| Deployment | Docker Compose on Linux; one backend service, one frontend service, one PostgreSQL service. |
| Hash algorithm | SHA-256 for all v1 content hashes and signatures. Store the algorithm with every digest. Do not implement SHA-1 generation in v1. |
| Default checksum policy | Hash regular files only when at least two inventoried file paths have the same size within the selected scan. A unique-size file remains unhashed unless the operator explicitly requests additional hashing. |
| Signature coverage | Match all available valid hashes automatically. Offer a separate, explicitly enabled signature-candidate hashing option and an on-demand “Check known signatures” action. Neither is silently enabled. |
| Meaning of a duplicate | Same size and full-file SHA-256: `HASH_IDENTICAL`. This is evidence of identical content, not permission to delete. Optional manual byte comparison provides additional evidence. |
| Content search | Implement actual text-content indexing and search, explicitly enabled by the operator. Metadata-only scanning does not read file bodies. v1 text-format limits are defined in section 10. |
| Scan comparison scope | One scan, potentially containing several source roots. Never group historical observations from different scans as if they were simultaneous copies. |
| Stop | “Pause” requests a durable safe checkpoint and is resumable. “Cancel” is terminal and retains already committed results. |
| Source safety | Scan roots are read-only bind mounts. No delete, move, rename, quarantine, chmod, hard-link replacement, file-writing, or script-execution feature. |
| Review lists | Export descriptive data, never executable deletion scripts or shell commands. |
| Extensibility | Internal interfaces and versioned schemas, not a runtime plugin marketplace or microservice platform. |

SHA-256 is selected because SHA-1 has known weaknesses and NIST recommends moving to SHA-2 or SHA-3. This application uses SHA-256 as a full-file content fingerprint; hashes are not digital signatures or proof of authorized content. [R1]

### 1.2 In scope

The complete v1 includes recursive inventory, durable scan control and restart recovery, candidate selection by size, full SHA-256 hashing, duplicate groups, file browsing, metadata/text search, memo and tag editing, a signature catalog, explicit additional hash requests, space analysis, review plans, safe exports, authentication, Docker deployment, tests, and operational documentation.

### 1.3 Out of scope

Do not implement filesystem deletion or mutation, executing exported plans, shell command execution, malware execution, perceptual image/video matching, OCR, image classification, automatic cloud submissions, archive unpacking, Office/PDF parsing, filesystem monitoring, continuous automatic rescanning, cross-scan duplicate grouping, multi-host agents, multi-tenant accounts, internationalization, or external plugin loading. These require separately approved specifications.

A file inside an archive is not an individual inventory record in v1. The archive itself can be inventoried, hashed, and matched as a normal file. Encrypted files can be hashed as stored bytes, but their plaintext cannot be searched.

## 2. Technology and repository architecture

### 2.1 Baseline

Use the following compatible release lines, then pin exact versions and image digests during milestone M0. The version statements below are a checked baseline, not permission to use floating `latest` dependencies.

| Component | Baseline and rule |
|---|---|
| JVM | Java 21 runtime/toolchain. |
| Spring Boot | Stable 4.1.x; the official documentation inspected lists 4.1.1. Use its dependency management. |
| Groovy | Groovy 5.x, as managed by the selected Boot BOM; the inspected 4.1.1 BOM lists 5.0.8. |
| Backend build | Gradle Wrapper, Groovy DSL. Boot 4.1 supports Gradle 8.14+ in the 8.x line or 9.x. Pin a tested version. |
| HTTP | Spring MVC, Bean Validation, Spring Security, JSON REST. |
| Persistence | PostgreSQL 18, Flyway SQL migrations, Spring JDBC/JdbcClient or NamedParameterJdbcTemplate. No mandatory ORM. |
| Frontend | Angular 22.0.x with matching Angular Material/CDK. Pin a tested patch. |
| Frontend toolchain | Node 24.x at least 24.15.0, TypeScript 6.0.x, compatible RxJS 7.x. Commit `package-lock.json`; use `npm ci`. |
| Tests | JUnit Jupiter tests written in Groovy; Testcontainers PostgreSQL; frontend unit tests; Playwright browser tests. Spock is not mandatory. |
| Linux filesystem binding | A small Groovy adapter using a pinned JVM native binding such as JNA for the descriptor-based Linux operations in section 5. Do not scatter native calls throughout the application. |
| Runtime | Docker Engine and Compose v2 on a supported Ubuntu Linux host; Linux/amd64 is the required initial target. |

The Spring requirements and managed Groovy coordinates are documented in [R2] and [R3]. Angular's Node/TypeScript compatibility matrix is documented in [R4]. Recheck these primary sources when implementation begins; record any necessary change in an ADR and run the compatibility test suite. Do not move to prereleases.

### 2.2 Deployment topology

The browser talks only to the frontend origin. An unprivileged Nginx frontend serves Angular and forwards `/api/` to the backend. The backend accesses PostgreSQL over a private Compose network and reads explicitly mounted source roots. PostgreSQL is not published to the host. The frontend never mounts source data.

Use a modular monolith. The backend contains separate packages for `roots`, `inventory`, `jobs`, `hashing`, `duplicates`, `signatures`, `annotations`, `search`, `planning`, `exports`, `security`, and `operations`. Do not add Redis, Kafka, Elasticsearch, a separate scheduler, or Spring Batch unless a documented need cannot be met by the prescribed PostgreSQL work queue.

Application services, controllers, persistence code, filesystem orchestration, and tests are written in Groovy. Use `@CompileStatic` for production code except narrowly justified framework integration. Use Java libraries normally. No user-provided Groovy scripts, `GroovyShell`, dynamic code evaluation, or invocation of programs found in the scanned directories.

Suggested repository layout:

```text
fnord-dedup/
  AGENTS.md
  README.md
  LICENSE                         # preserve the existing repository license
  backend/
    build.gradle
    settings.gradle
    gradlew, gradlew.bat, gradle/wrapper/
    src/main/groovy/.../
    src/main/resources/application.yml
    src/main/resources/db/migration/
    src/test/groovy/.../
    Dockerfile
  frontend/
    src/app/
    package.json, package-lock.json
    Dockerfile
    nginx.conf
  deploy/
    compose.yaml
    compose.sources.example.yaml
    application.example.yml
    .env.example
  scripts/
    preflight
    test-all
    smoke-test
  docs/
    SPECIFICATION.md
    IMPLEMENTATION_PLAN.md
    ACCEPTANCE_TESTS.md
    API.md
    OPERATIONS.md
    VERIFICATION.md
    adr/
```

`docs/SPECIFICATION.md` is authoritative. `AGENTS.md` supplies implementation guardrails, not an alternative product definition. `API.md` must agree with the implemented OpenAPI contract. Never overwrite the existing license.

### 2.3 Internal extension boundaries

Define narrow interfaces such as `ReadOnlyFileAccess`, `InventoryRepository`, `WorkQueue`, `HashCalculator`, `SignatureMatcher`, `TextExtractor`, `SpaceEstimator`, and `ExportWriter`. Instantiate implementations through Spring. Keep persisted job payloads and import/export schemas versioned. Do not build general-purpose plugin loading in v1.

## 3. Core invariants

**INV-01 — Source immutability.** No operation in the application, UI, API, startup scripts, or generated exports changes scanned source bytes, names, permissions, timestamps intentionally, links, or directory structure. The application must not mount the Docker socket, use privileged containers, or offer source filesystem write methods.

**INV-02 — Read-only enforcement.** A source root is not eligible for scanning or reading unless its effective mount is verified read-only. Reject writable included submounts. Do not test read-only status by trying to create or delete a probe file in a real source root. Docker recursive read-only behavior depends on the kernel and mount configuration; the application must validate the actual container view. [R5]

**INV-03 — Evidence is versioned.** A checksum belongs to a specific scan observation and to the exact metadata observed around the read. A checksum computed for an earlier observation is not silently attached to a later scan.

**INV-04 — Missing evidence is not negative evidence.** `NOT_HASHED`, `NOT_INDEXED`, unreadable, stale, partial, and unavailable are not “not duplicated,” “no signature match,” or “clean.”

**INV-05 — Durable control.** A scan/job is recoverable from PostgreSQL state after process loss. An in-memory queue or flag is never the only record of work or a pause/cancel request.

**INV-06 — Safe publication.** Partial hashing, grouping, import, and export results are never published as complete. Aggregate revisions are atomically switched into visibility after completion.

**INV-07 — Root confinement.** All source access remains beneath a configured, authorized root and never follows symbolic links. Normalizing a string or checking `startsWith` is insufficient.

**INV-08 — Honest space analysis.** Hard links, aliases, snapshots, sparse allocation, compression, and shared extents must not be reported as guaranteed reclaimable space. Exact physical reclamation is unknown in v1.

**INV-09 — Historical isolation.** The same file observed in two scans is not two simultaneous copies. Every default explorer, search, duplicate query, plan, and export has an explicit scan context.

**INV-10 — English interface, unrestricted names.** No translation system. Unicode and Linux filename bytes still require correct handling; English-only is not an ASCII filename restriction.

Internal database records and application-owned export/scratch storage are separate from scanned sources. Normal temporary-file cleanup may occur only in those application-owned areas. v1 has no destructive source operation or automatic database-history purge feature.

## 4. Configured sources, paths, and inventory semantics

### 4.1 Source registry

Administrators add a source through Compose bind-mount configuration and backend configuration. The web UI can select an existing source but cannot create arbitrary host mounts or turn an arbitrary path into a scan root.

Each configured source has a stable `sourceId` UUID, a stable `sourceInstanceId` UUID identifying the backing dataset, a short `key`, a display label, an absolute `containerPath`, optional `hostExportPrefix`, `enabled`, and a `crossMounts` flag defaulting to false. A new disk/dataset replacing an old source must use a new source instance. Do not infer permanent source identity from Linux device numbers alone.

Record a source-config revision and a scan-specific snapshot of configuration. A change to the binding or source identity while a job is active pauses affected work with `SOURCE_CONFIGURATION_CHANGED`. Disabling a source preserves its history and annotations. Removing a mount makes its current availability `UNAVAILABLE`, not “all files removed.”

At startup and before resume, validate that enabled roots exist, are directories, are accessible to the runtime UID/GIDs, are read-only, do not contain application data volumes as included scan content, and do not overlap another selected root. Reject obvious nested roots and alias roots. Detect the same physical directory reached through another mount/bind alias and do not traverse it twice within a scan. Surface an explicit overlap/alias warning rather than inflating file counts.

### 4.2 Path identity

Persist a `file_location` for each configured source root, source instance, and relative path. Source ID is part of location identity because two roots on the same dataset may each contain a different `photo.jpg`. It represents a location, not immutable content. Keep an exact raw relative path, a safe display representation, parent location, raw basename, and extension for supported textual names.

Linux paths are byte sequences. Use raw filename bytes for identity, traversal, and lossless export; use UTF-8 decoding where valid and a visibly escaped representation otherwise. JSON exposes `relativePathBytesBase64` when needed and MUST include it in machine-readable export rows. Never reconstruct a native filename from a lossy display string. Do not apply Unicode normalization or case folding to filesystem identity.

Support spaces, quotes, tabs, newlines, leading hyphens, backslashes, composed/decomposed Unicode, and invalid UTF-8 filename bytes. NUL cannot be a filename byte. Display control characters safely. Use opaque location/observation IDs in browser navigation and API routes, not raw filenames in URL path segments.

For indexing, use source-root ID, source-instance ID, parent ID, and raw basename as the location uniqueness key. Enforce uniqueness for root rows with null parents as well, using `NULLS NOT DISTINCT` or an equivalent partial unique index. Full relative paths may be large: store them without a full-path B-tree uniqueness index. A fixed-width path digest may accelerate lookup but is not by itself authoritative identity; compare the underlying bytes on a digest collision.

### 4.3 Scan scope

A scan contains one or more whole registered source roots. Subtree filters are available for browsing, search, and plans; arbitrary subtree scan scheduling is not required in v1. Duplicate-size selection spans all roots in that scan, so same-size files on different selected roots are candidates.

Include hidden files/directories by default. No default filename, extension, or size exclusions. Record directories, regular files, symlinks, and other encountered entries. Do not follow symlinks, including links targeting locations inside the root. Record the link target as metadata when safely readable. Do not open FIFOs, sockets, block devices, or character devices for content.

With `crossMounts=false`, inventory a nested mount boundary as a skipped directory boundary and do not descend into it. With explicit `crossMounts=true`, validate every included mount as read-only first. The UI and exported coverage summary must show excluded boundaries. Sources on network filesystems may be scanned when their mounts satisfy these rules, but unavailable or blocked I/O must be reported honestly.

### 4.4 Required metadata

For every successfully observed entry, persist scan ID, observation ID, source/source-instance ID, location ID, parent location ID, exact relative path, display filename and path, entry type, byte size for regular files, mtime, observed-at time, and discovery status.

Also persist, when available: ctime, birth time, device ID, inode, link count, allocated block count/bytes, numeric UID/GID, mode bits, filesystem type, mount identity, and symlink target. These fields support identity checks and space analysis. `ctime` is not creation time. An unavailable field is null with a capability indicator, not zero. Linux exposes size, links, blocks, and multiple timestamps via file metadata interfaces. [R6]

Store display/query timestamps as UTC `timestamptz`, but preserve native mtime and ctime as `epochSeconds BIGINT` plus `nanoseconds INTEGER` for exact comparisons. PostgreSQL timestamps have microsecond precision, so they must not be the only source-change fingerprint. [R7]

Metadata errors should still produce a path/type-unknown observation when the name was discovered, plus a structured error. A directory that cannot be listed has an error and incomplete subtree coverage. Continue with unrelated accessible entries.

### 4.5 History and annotations

Each new scan creates new observations. Never overwrite a prior scan's metadata with the latest filesystem state. Default browsing is an explicitly selected scan; a history view may compare records without using them as duplicate copies.

Manual memos/tags belong to a `file_location` and persist when that path is rescanned in the same source instance. The UI must describe them as location annotations. If new content or identity is detected at that path, retain the note but display “File changed; review existing annotations.” Rename/move detection and automatic annotation transfer are out of scope. Signature-derived findings belong to scan observations, so an old finding cannot silently label replacement content.

## 5. Read-only filesystem access and consistency

### 5.1 Linux access adapter

Implement all source reads behind `ReadOnlyFileAccess`. Its public contract permits listing directories, reading metadata/link targets, opening a regular file read-only, reading a bounded buffer, and closing resources. It has no write/delete/rename/execute methods.

The required Linux implementation uses descriptor-relative traversal and open operations with no-symlink/root-confinement semantics. `openat2` provides `RESOLVE_BENEATH` and symlink restrictions; descriptor-based `statx`/`fstat` can inspect the object actually opened. A small native JVM binding is appropriate; pure pathname checks must not be substituted as if equivalent. [R8] [R9]

The adapter must:

- Pin the authorized root directory handle and open path components beneath it. Reject symlink components and magic links. Never accept an absolute path from the client as a file-open target.
- Retain filename bytes losslessly during directory enumeration. Resolve deep paths component by component where necessary rather than relying on one concatenated native path string.
- Open with read-only, close-on-exec, and no-follow restrictions. Guard file-type replacement races: avoid blocking on a FIFO and verify the opened descriptor is a regular file before any content read.
- Read pre/post metadata from the same opened descriptor. Revalidate the directory entry to ensure it still resolves to the observed object before accepting evidence.
- Fail closed when required confinement/metadata support is unavailable. Report a clear unsupported-platform error; never fall back to following symlinks.

Use a reviewed JVM native binding and document supported Linux ABI/architecture. Native libraries must be packaged or loaded from an application-controlled location, not from scanned directories. Do not add JVM access to private JDK internals as a shortcut for path handling. Java `SecureDirectoryStream` documents race-resistant directory-relative operations, but ordinary Java pathname APIs alone do not satisfy every required descriptor/raw-byte operation. [R10]

### 5.2 Live files are not a snapshot

A read-only bind mount prevents this application from writing through that mount; it does not prevent other host processes from changing the source. Therefore, each scan is an observational inventory over an interval, not a guaranteed point-in-time filesystem snapshot.

Check source metadata against the scan observation immediately before hashing, indexing, byte comparison, or validation. Check again after the read. If type, size, mtime, ctime, relevant identity, or directory-entry identity differs, mark the operation `CHANGED`/`STALE` and do not accept the result for that observation. Preserve the original observation and append evidence of the change; a new scan is the normal way to inventory the new version.

Do not claim these checks detect an adversary who can change and restore data/metadata between observations. For stronger consistency, the operator may supply an already-created read-only filesystem snapshot. The software must not create, modify, or destroy snapshots itself. Mount and snapshot labels should appear in reports.

### 5.3 Read limits and error behavior

Never load an entire file into a Groovy `byte[]`, string, or memory-mapped buffer just to hash it. Read sequentially in bounded chunks, normally 4 MiB. Do not shell out to `find`, `sha256sum`, `grep`, or source-provided scripts. Retry transient reads with bounded attempts; permission failures, missing files, and changed files are explicit final work-item outcomes, not fabricated successes.

All file handles, directory streams, and database resources must close on success, error, pause, cancellation, and shutdown. Cap simultaneous open file handles. A blocked filesystem syscall may not be instantly interruptible; the UI must say “Waiting for I/O” instead of claiming the worker has stopped.

## 6. Durable jobs, progress, pause, resume, and cancel

### 6.1 Job model

Use one durable job framework for scan execution, manual hash requests, signature-candidate checks, text indexing, byte comparison, grouping, planning materialization, and exports. v1 runs one filesystem-intensive top-level job at a time; it may use bounded workers internally. Metadata browsing and annotation editing remain available while jobs run.

Persist `job`, `work_item`, `job_event`, and typed result records in PostgreSQL. Work claims use short transactions and row locking, for example `FOR UPDATE SKIP LOCKED`, followed by filesystem work outside the transaction. PostgreSQL documents `SKIP LOCKED` as appropriate for avoiding contention among queue consumers; it is not a consistent general-purpose reporting view. [R11]

Each claim carries an owner, monotonically increasing lease token, and expiry. A worker can commit results only while its token still owns the work item. Heartbeat/renew normally every 10 seconds with a 60-second lease; record the actual configured values. Fence late workers after recovery. Claim/result writes and counters are idempotent.

Do not keep one database transaction open for a whole directory tree or while reading a large file. Use a database coordination lock so an accidental second backend process cannot become a second scheduler. No horizontal scaling promise in v1.

### 6.2 Status and phase are separate

Job statuses:

```text
QUEUED
RUNNING
PAUSE_REQUESTED
PAUSED
CANCEL_REQUESTED
CANCELLED
INTERRUPTED
COMPLETED
COMPLETED_WITH_ERRORS
FAILED
```

A scan job has phases `INVENTORY`, `CANDIDATE_SELECTION`, `HASHING`, `SIGNATURE_MATCHING`, and `GROUPING`. Enabled text indexing is a separate child job after the core scan, with its own coverage/status; a content-index failure must not hide a completed duplicate inventory. Optional signature-candidate hashing is part of `HASHING` only when explicitly selected at scan creation.

Required transitions:

| Current status | Action/event | Result |
|---|---|---|
| QUEUED | Start | RUNNING |
| QUEUED | Pause | PAUSED |
| RUNNING | Pause | PAUSE_REQUESTED, then PAUSED after workers checkpoint |
| PAUSED or INTERRUPTED | Resume | Validate sources/config; queue remaining work; RUNNING when scheduled |
| QUEUED or PAUSED | Cancel | CANCELLED |
| RUNNING or PAUSE_REQUESTED | Cancel | CANCEL_REQUESTED, then CANCELLED after workers stop |
| INTERRUPTED | Cancel | CANCELLED |
| RUNNING | All required work finalized | COMPLETED or COMPLETED_WITH_ERRORS |
| Active nonterminal status | Unclean process loss | Recover to INTERRUPTED; retain pending cancel intent |
| RUNNING | Unrecoverable job/configuration error | FAILED with diagnostic information |

Terminal jobs do not resume. The UI offers a new scan or an explicit follow-up retry job rather than resurrecting cancelled/completed history. A repeated request for an already-requested pause or cancel is idempotent. Incompatible state changes return HTTP 409. Cancel wins a pause/cancel race. Completion versus cancellation is serialized by the job-row transaction; a late cancel must not relabel a completed job.

### 6.3 Safe points

**Inventory:** commit discovered entries and child-directory work in batches, normally 500 entries or one second. A directory becomes complete only after full enumeration and after all emitted entries/child work are committed. On interruption, re-enumerate that unfinished directory from its beginning; do not persist an array offset into a live directory listing. Uniqueness constraints prevent duplicate observations and child jobs. The first committed observation for a path in the scan is retained; conflicting metadata on replay marks the observation unstable rather than silently replacing it.

**Hashing/indexing/verification:** check control requests at bounded chunk boundaries. On pause/cancel, close the stream and discard the unfinished digest/extraction/comparison. Resume that file from byte zero. Already committed complete results are retained. Do not serialize provider-specific SHA-256 internal state or treat a partial digest as valid. Physical bytes read may exceed useful completed bytes after retries; track them separately.

**Database analysis:** work in bounded batches into an unpublished result revision. Pause between batches. Publish only a complete generation with an atomic revision switch.

**Exports:** build an application-owned temporary artifact. Pause/cancel must never expose it as a complete export. Resume may restart file assembly from persisted selected rows rather than append blindly.

Under healthy local I/O, target acknowledgement of a pause/cancel request within two seconds and checkpointing within five seconds. These are acceptance targets, not guarantees for a blocked kernel/network read. Expose non-responsive workers and last heartbeat. Never use unsafe thread termination.

### 6.4 Process restart and shutdown

On SIGTERM stop claiming work, record the desired safe stop, checkpoint/close active resources, and exit within the configured Compose grace period when I/O permits. An interrupted active job becomes `INTERRUPTED` on startup and requires explicit operator resume by default. A persisted cancellation request is completed as cancellation during recovery; it is not resumed. Expired claims are recovered with fencing, not discarded results.

Before resume, validate root identity, mount read-only status, config revision, and source availability. Recheck each file when it is reopened. Configuration incompatibility explains the block and never substitutes another path silently.

### 6.5 Progress UI contract

Progress is a durable snapshot with job ID, scan ID, state, phase, start/update times, current source, active file display path, discovered directories/files, completed/skipped/error entries, candidate count/bytes, completed hash count/useful bytes, total physical bytes read, indexed count, pending/running work, elapsed time, and last heartbeat.

Discovery has an unknown total: show counts and rate, not a fabricated percentage. After candidate selection, hashing can show completed candidate count and bytes against known totals. Failed/skipped files count as resolved work, not successful hashes. Rates/ETA are estimates; omit ETA without enough data. Pausing preserves counters.

Use authenticated REST polling no more often than every ten seconds. Polling stops when the browser view is closed; server work does not. Browser reconnect reloads durable state. SSE is optional future enhancement, not a v1 requirement.

## 7. Three-step scan pipeline

### 7.1 Step 1 — Inventory

Validate and freeze the source/configuration selection, create the scan ID and durable root-directory work, then enumerate recursively according to sections 4–6. Persist useful metadata for every discovered entry. This phase reads directory entries and metadata only: no hashing, MIME sniffing, thumbnails, archive inspection, or text extraction.

Freeze inventory once discovery work has reached final outcomes. Mark coverage per source and directory as `COMPLETE`, `PARTIAL`, `UNAVAILABLE`, or `EXCLUDED_BY_POLICY`. A completed traversal with permission errors is not a fully covered source. No file in an unvisited subtree is assumed absent.

Do not let a browser request rerun discovery synchronously. Partial inventory is browsable with a prominent “Scan incomplete” badge. Only committed rows appear.

### 7.2 Step 2 — Select same-size candidates and compute SHA-256

Let `F` be the regular-file observations in this scan with a known size and usable discovery metadata. The default candidate set is:

```text
D = { f in F : count(g in F where g.sizeBytes == f.sizeBytes) >= 2 }
```

Materialize this selection in the database after inventory. Use a size index and database aggregation; do not load all file records into memory. Count distinct observed locations, not duplicate rows from retries. Null sizes never form a candidate group.

Create one idempotent hash task per candidate observation and algorithm. In v1, read each eligible file path independently, including hard-linked paths; an optimization to reuse another path's hash is not required and must not be introduced without equivalent evidence and tests. Hard links are handled separately for space accounting.

Default behavior examples:

| Inventory | Required hash behavior |
|---|---|
| One 9-byte file, no other 9-byte files | Remains `NOT_REQUESTED_UNIQUE_SIZE`. |
| Three 9-byte files | Hash all three, even if their names/extensions differ. |
| Two empty files | Obtain the complete SHA-256 of both empty streams and record successful timestamps. |
| One empty file | Remains unhashed unless explicitly requested. |
| Same-size files on different selected roots | Hash all such files within the scan. |
| Same-size files only in two different scans | Do not create a joint candidate set. |

For each attempt persist start time, completion time, algorithm, bytes read, task reason, outcome, pre/post file fingerprints, and error information. Store digest bytes only for a successful full read accepted against the observation. The UI/API displays lowercase hexadecimal SHA-256, exactly 64 hex characters, and its actual completion timestamp. A newly created database row is not a new checksum time.

A hash attempt succeeds only when the opened object is regular, its pre-read fingerprint matches the observation, all expected bytes are read, no extra bytes appear before EOF, post-read metadata agrees, and the directory entry still refers to that object. A changed, truncated, enlarged, replaced, removed, or unreadable file has no accepted digest for the failed attempt.

There is no cross-scan checksum cache in v1. This avoids calling an old hash current merely because size/mtime appear unchanged. Within the same observation, repeated requests may reuse an already accepted complete result unless `forceRehash=true`; reuse must retain the original checksum timestamp. A forced fresh read that contradicts the earlier digest invalidates the observation's active evidence and its dependent results rather than silently choosing one digest.

### 7.3 Explicit additional hashing

Provide these actions:

**Calculate checksum:** select one file or a frozen, bounded selection of files, including unique-size files. This is an explicit manual request. Return a job ID and allow pause/resume/cancel.

**Check known signatures:** explicitly hash previously unhashed files whose sizes occur in a selected snapshot of enabled signatures, then match them. Clearly show estimated files/bytes before confirmation. Files whose sizes cannot match the selected catalog do not need hashing for this purpose.

**Scan option `includeSignatureCandidates`:** default false. When the operator enables it, add the signature-size candidate set to the normal duplicate set. Capture consent in the scan's options and audit event. This choice permits automatically finding a unique-size known file during that scan; it is not a silent change to the default checksum policy.

Formally, when that option is enabled:

```text
S = { f in F : an enabled signature in the scan's catalog revision has size f.sizeBytes }
hashCandidates = D union S
```

Use a reason set, such as `DUPLICATE_SIZE`, `SIGNATURE_SIZE`, `MANUAL`, and `FORCED_RECHECK`, rather than issuing two full reads for the same observation just because it has two reasons.

### 7.4 Step 3 — Group duplicates

Group only eligible observations with accepted, non-invalidated SHA-256 evidence using:

```text
(scanId, sizeBytes, algorithm, digest)
```

A group is a duplicate group when it has at least two distinct file locations. Same size alone, a digest prefix, a failed attempt, or a null digest does not qualify. File names and extensions are irrelevant to content grouping.

Grouping creates an immutable `analysisRevision`. A later manual hash or forced recheck schedules a new grouping revision. Preserve old evidence for history, atomically publish the new revision, and label plans pinned to the old revision as needing review when affected. The UI may show provisional groups during a scan but cannot mistake them for a finalized analysis.

## 8. Duplicate evidence and byte comparison

For each group show size, algorithm, digest, path count, independently stored object count where determinable, hard-link/alias information, participating roots, accepted checksum timestamps, evidence level, error/staleness information, and theoretical duplicate-copy bytes.

Evidence levels are `HASH_IDENTICAL`, `BYTE_VERIFIED`, `VERIFICATION_FAILED`, and `STALE`. A skipped/unhashed file has no duplicate evidence, not negative evidence. Verification is specific to the observations and validation window and does not prove future equality.

Provide a manual **Verify group byte-for-byte** action. Compare one selected reference object to each other distinct object using bounded read buffers. Independently validate each opened object before and after comparison. Persist verification pairs and outcomes. Label the group `BYTE_VERIFIED` only when all required pairs in that revision successfully agree. Pause/cancel discards incomplete comparisons; a resumed comparison starts at byte zero.

If byte comparison disagrees despite matching SHA-256 metadata, report a serious evidence conflict, keep the observations for investigation, and exclude the group from normal deduplication plans until reviewed/recomputed. Never assume this cannot happen in tests or after source changes.

Matching contents do not imply that ACLs, owners, extended attributes, filenames, or application references are interchangeable. Exports must always require independent verification before any external destructive action.

## 9. User-maintained signature catalog

### 9.1 Signature model

A signature represents a user-defined known file fingerprint. Fields:

| Field | Rule |
|---|---|
| `id` | UUID. |
| `revision` | Monotonic record revision. |
| `name` | Required human-readable label, 1–200 characters. |
| `memo` | Optional plain text, up to 20,000 characters. |
| `tags` | Zero or more reusable tag references. |
| `sizeBytes` | Required nonnegative integer, represented as a decimal string in JSON. |
| `algorithm` | Required; only `SHA-256` supported in v1. |
| `checksum` | Required 64-character hexadecimal SHA-256; normalize to lowercase. |
| `filename` | Optional exact advisory filename, not required for content matching. |
| `filenameMatchMode` | `ADVISORY` by default, or explicitly selected `REQUIRED_EXACT`. |
| `enabled` | Default true. |
| `createdAt`, `updatedAt` | UTC timestamps. |
| `origin` | `MANUAL`, `FROM_OBSERVATION`, or `IMPORT`, plus optional source note. |

The normal confirmed match predicate is exact `sizeBytes + algorithm + checksum`, independent of filename. Therefore, a renamed copy still matches. When `REQUIRED_EXACT` is explicitly selected, also require an exact filename match; the UI must warn that renamed copies will no longer be matched by that rule. Arbitrary regex/glob rules are not part of v1.

Store filename bytes when a signature is created from an observation with a non-UTF-8 basename; imports may supply `filenameBytesBase64`. A signature with an ordinary Unicode filename uses its exact UTF-8 bytes. Case sensitivity follows exact matching, not a locale-dependent comparison.

Distinct signature records may intentionally have the same fingerprint but different labels or provenance. Return all matching records. Never silently discard duplicate fingerprints. Imported identical record IDs are handled by explicit import policy, not implicit overwriting.

### 9.2 Matching and coverage

Automatically match available accepted hashes after scan hashing and after a new manual hash succeeds. A signature added or edited later triggers a database-only rematch of existing eligible hashes. This rematch must not read additional file contents without the configured or explicit authorization described in section 7.3.

Freeze a catalog revision for each matching job and store the matched signature revision. If the catalog changes while that job runs, finish consistently against its captured revision, then queue a new database rematch. Preserve historical findings and mark current/obsolete status correctly. Disabling a signature removes its active derived labels from the current presentation after rematch but retains finding history.

Report signature coverage with separate fields, not one misleading boolean:

```text
matchStatus: MATCHED | NO_MATCH_IN_CHECKED_CATALOG | UNDETERMINED
checkStatus: CHECKED_HASH | EXCLUDED_BY_SIZE | HASH_REQUIRED |
             STALE | READ_ERROR | CATALOG_NOT_CHECKED
catalogRevision: <revision>
```

An unhashed file with a catalog-matching size is `HASH_REQUIRED`, not confirmed. An unhashed file whose size occurs nowhere in a catalog revision may be `EXCLUDED_BY_SIZE` for that catalog alone; this does not mean it is safe or not sensitive. A filename resemblance may be displayed as an advisory candidate, never as a verified content match.

### 9.3 Catalog UI and import/export

Provide list/search, detail/edit, enable/disable, and create-from-file actions. Create-from-file uses an accepted full SHA-256; an unhashed file first requires an explicit checksum job and user continuation. Never invent a fingerprint from its name or size.

Import/export JSON and CSV signature catalogs. Validate the entire upload into staging, show a dry-run summary and row-level errors, then require explicit apply. Reject unsupported algorithms, malformed hashes, negative or overflowing sizes, invalid base64, and unknown schema versions. Formula-like metadata is inert data, never executable content. v1 imports are atomic: no partial catalog update on validation failure. A queued job may stage a large import, but only the final apply transaction changes the active catalog.

The canonical JSON catalog envelope is `{ "schemaVersion": 1, "exportedAt": "<UTC timestamp>", "signatures": [...] }`; each array item follows the record fields below. `exportedAt` is optional on import. CSV uses a UTF-8 header with `schemaVersion,id,revision,name,memo,tagsJson,sizeBytes,algorithm,checksum,filename,filenameBytesBase64,filenameMatchMode,enabled`; `tagsJson` is a JSON array of tag-label strings inside a properly quoted CSV cell. Missing optional cells are empty; `enabled` is `true` or `false`. Reject ambiguous duplicate headers. Both formats use decimal integer sizes, not formatted human-readable sizes. Generated IDs are permitted for new imported records whose `id` is empty, but update-by-ID requires an ID and expected revision.

A catalog import is application data, not an uploaded file to execute or scan. Permit only documented parsers and configured upload limits. Default conflict policy is `REJECT_EXISTING_ID`; an explicit `UPDATE_BY_ID` mode requires expected revisions. Do not import destructive commands.

Example signature export record:

```json
{
  "schemaVersion": 1,
  "id": "c1e044a4-35d0-4fd8-bdb2-81115d74400d",
  "revision": 1,
  "name": "Known internal test file",
  "memo": "Review this file wherever the same content is found.",
  "tags": ["review", "internal-test"],
  "sizeBytes": "5",
  "algorithm": "SHA-256",
  "checksum": "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
  "filename": "hello.txt",
  "filenameMatchMode": "ADVISORY",
  "enabled": true
}
```

The example fingerprint is for the five UTF-8 bytes `hello`, without a newline. It is a benign test fixture, not a real prohibited-file signature.

## 10. Content indexing and search

### 10.1 Consent and separation

Metadata/checksum search is always available for stored observations. Actual content search requires an explicit **Index text content** request or enabling text indexing in scan options. Default is disabled to avoid unexpected full-file reads and replication of sensitive text into PostgreSQL.

Explain before enabling: “Text indexing reads supported files and stores searchable text in this application's database. It is independent of duplicate checksums.” Reading a unique-size file for indexing does not authorize generating/storing a SHA-256 for it. Do not fuse checksum generation into indexing unless that observation already has an authorized hash request.

### 10.2 v1 supported content

Support plain-text, source-code, and text-configuration files from a configurable extension allowlist: `.txt`, `.md`, `.csv`, `.log`, `.json`, `.xml`, `.html`, `.htm`, `.yaml`, `.yml`, `.toml`, `.ini`, `.conf`, `.properties`, `.java`, `.groovy`, `.js`, `.ts`, `.css`, `.scss`, `.sql`, `.sh`, `.py`, `.rb`, `.c`, `.h`, `.cpp`, `.go`, and `.rs`.

Read UTF-8, optionally with BOM, and BOM-marked UTF-16LE/BE. Unsupported or invalid encodings receive a clear status rather than arbitrary lossy conversion. HTML/XML are searchable as plain text; do not evaluate markup, run JavaScript, expand XML entities, or fetch resources. A configured extension does not guarantee text: reject binary-looking/undecodable payloads and enforce limits.

Defaults: maximum source file size 10 MiB; maximum stored extracted text 256 KiB of valid UTF-8; bounded read buffers; configurable per-file processing limit. If the source exceeds the input limit, `SKIPPED_TOO_LARGE`. If extraction exceeds the text limit, truncate at a valid character boundary and label `INDEXED_TRUNCATED`; do not claim the remaining file was searched. A blocked read has the same limitations as section 5.3.

PDFs, Office documents, archives, executable binaries, audio, images, and videos are not text-searchable in v1. They remain inventory/hash/signature candidates. The `TextExtractor` interface is the extension point for future sandboxed parsers; do not quietly add a broad unsafe parser dependency now.

### 10.3 Index representation and query semantics

Persist extraction result, input fingerprint, index timestamp, extractor/version, encoding, supported/truncated status, extracted text, and a PostgreSQL `tsvector`. Use the `simple` text-search configuration by default so source-code and mixed-language tokens are not assumed to be English prose. PostgreSQL provides tokenized full-text search with indexing; indexing does not make unsupported file formats searchable. [R12]

Provide two explicit content operators: token/full-text search over the indexed text, and literal substring search over the indexed text with a case-sensitive option. Use parameterized queries, bound query length, statement timeouts, and appropriate indexes. No user-defined SQL or unbounded regex engine in v1. Token search and literal substring search must not be described as equivalent.

States include `NOT_REQUESTED`, `QUEUED`, `INDEXING`, `INDEXED_FULL`, `INDEXED_TRUNCATED`, `SKIPPED_TOO_LARGE`, `UNSUPPORTED_FORMAT`, `UNSUPPORTED_ENCODING`, `BINARY_CONTENT`, `ERROR`, and `STALE`. Show indexed coverage beside search results. “No results” means no match within eligible indexed content, not no matching content anywhere in the filesystem.

Render only escaped text snippets with match highlighting and a strict length cap. Do not provide a raw arbitrary-file download/preview endpoint. Do not embed scanned HTML or images. Every stored text result is tied to the scan observation and is invalidated for current-evidence use when that observation becomes stale.

## 11. Memos, tags, and review state

Every file location supports an editable plain-text memo, reusable tags, and a review status: `UNREVIEWED`, `REVIEWED`, `KEEP`, or `REMOVAL_REVIEW`. These are application database attributes only. A `KEEP` location is protected from automatic removal-candidate selection within a plan. `REMOVAL_REVIEW` does not execute any action.

Tag labels are 1–64 characters, trimmed, unique by an explicitly documented normalized lookup key, and displayed in the entered form. This normalization never affects filenames. Memo limit is 20,000 characters. Use optimistic concurrency with `version`/`If-Match` so two tabs do not silently overwrite notes. Editing a memo retains an audit event; removing a tag association changes only application metadata.

Show manual annotations separately from signature-derived names, memos, and tags. Derived tags reference their finding/signature revisions. Do not copy them irreversibly into manual tags. Disabling or editing a signature cannot erase a user's independent memo or tag.

Support multi-select/bulk tagging and review-state updates on a frozen server-side selection. A filter-based bulk action resolves its target IDs transactionally rather than changing meaning while a scan is growing. Bulk operations show selected count and are audited.

## 12. PostgreSQL data model

The implementation must provide normalized, migrated tables equivalent to the following. Names may differ only when the mapping is documented. JSONB is appropriate for captured settings or error details, not a substitute for indexed size/hash/path/job columns.

| Entity | Required fields and relationships |
|---|---|
| `source_root` | UUID, source-instance UUID, key, label, enabled, container binding metadata, config revision, availability, created/updated timestamps. |
| `file_location` | ID, source-root and source-instance references, parent location ID, raw basename bytes, raw relative path bytes, safe path/name display, optional path digest, first-seen time. Stable across scans of the same configured location. |
| `scan` | UUID, scan job ID, label, options snapshot, catalog revision selection, source-config snapshot, created/start/end timestamps, inventory freeze time, coverage summary, published analysis revision. |
| `scan_source` | Scan/root references, source identity/config snapshot, root-observation reference, coverage, start/end/errors. |
| `scan_entry` | ID, scan ID, location ID, type, size, metadata timestamps plus exact second/nanosecond fields, identity/link/allocation metadata, discovery outcome, directory coverage status, observed time, instability flag. Unique scan/location. |
| `observation_validation` | Entry ID, evidence revision, validation time/type, current validity outcome, fingerprint/error details. Append-only evidence; invalidation does not rewrite original inventory facts. |
| `hash_attempt` | ID, entry ID, job/task ID, algorithm, reason set, start/end timestamps, bytes read, outcome, nullable accepted digest, pre/post fingerprints, error. |
| `accepted_hash` | Entry ID + algorithm unique active pointer to an accepted attempt, evidence revision, active/invalidated status. Historical attempts remain. |
| `job` | UUID, type, parent/scan references, schema/payload revision, state, phase, desired action, captured options, counters, timestamps, heartbeat, error summary, optimistic version. |
| `work_item` | ID, job ID, task kind, deduplication key, target ID, state, attempts, lease owner/token/expiry, next eligible time, checkpoint payload, outcome. |
| `job_event` | Ordered event ID, job ID, event type, time, actor, structured payload. No raw file body. |
| `analysis_revision` | ID, scan ID, input evidence revision/cutoff, build state, completeness, created/published timestamps. |
| `duplicate_group` | ID, analysis revision, size, algorithm, digest, path/object counts, evidence summary, logical duplicate-copy bytes. Unique revision/size/algorithm/digest. |
| `duplicate_member` | Group/entry references, accepted hash attempt ID, physical-object/alias classification. Unique group/entry. |
| `byte_verification` | Pair/reference IDs, expected hash attempts, job ID, pre/post validation references, outcome, bytes compared, timestamps. |
| `file_annotation` | Location ID, memo, review state, optimistic version, created/updated timestamps. |
| `tag`, `file_tag` | Tag ID/label/normalized key; unique manual location/tag association. |
| `signature`, `signature_revision`, `signature_tag` | Stable UUID, current enabled/revision pointer, immutable rule/metadata revisions and tag associations. |
| `signature_import`, `signature_import_row` | Upload/staging ID, schema, actor, validation job/state, base catalog revision, row number, validated proposed fields, expected ID/revision, errors, apply outcome. No active changes until apply. |
| `signature_match` | Entry/evidence revision, signature/revision, matching job/catalog revision, match time, active/obsolete state. |
| `signature_check` | Entry/evidence/catalog revision, coverage/match status, check time. Stores negative/undetermined scope explicitly. |
| `content_index` | Entry/evidence reference, extractor version, encoding, status, input/index sizes, truncated flag, text, tsvector, indexed time, validation/error. |
| `selection`, `selection_member` | Opaque selection token, actor, scan/input revision, filter snapshot, expiry, captured entry IDs; used for bounded/frozen bulk actions and jobs. |
| `review_plan` | UUID, type, scan/analysis revision, label, filter/policy snapshot, version, draft/stale state, actor/times, totals. |
| `review_plan_item` | Plan/entry reference, duplicate group/object identity where relevant, disposition, keeper reference where applicable, reason, expected metadata/hash evidence. |
| `export_snapshot_row` | Export/job reference, sequence, captured entry ID and immutable output fields/evidence; supports streaming/restart without rereading mutable annotations. |
| `export_job_artifact` | Job/export ID, source plan/filter revision, format/schema, internal artifact key, build/ready state, row count, artifact digest, created/finished times. |
| `audit_event` | Actor, operation, entity ID, time, correlation ID, bounded before/after application-data changes. Do not include file bodies or credentials. |

### 12.1 Data types and constraints

Use `BIGINT` for individual nonnegative file sizes that fit the supported filesystem/API range; reject invalid/overflowing values. Use `NUMERIC(38,0)` or equivalent exact arithmetic for aggregate byte totals. In Groovy use `long` with checked conversion for individual sizes and `BigInteger`/exact `BigDecimal` for totals, never floating-point arithmetic. PostgreSQL exact numeric and integer ranges are documented in [R13].

In JSON, represent byte sizes/totals, inode/device identifiers where needed, and large database numeric IDs as decimal strings to avoid JavaScript precision loss. UUIDs are strings. Angular uses `BigInt` or a tested exact-decimal implementation for byte arithmetic and only converts safe bounded values for display percentages.

Store SHA-256 as 32-byte `BYTEA` with algorithm/length validation. Accept/display 64 lowercase hex characters at the API boundary. Store potentially unsigned 64-bit inode values as `NUMERIC(20,0)` or exact text, not a signed `BIGINT` that silently overflows. mtime/ctime nanoseconds must be between 0 and 999,999,999.

Foreign keys enforce scan/entry/group relationships. A group member must belong to the group's scan and accepted evidence revision. Unique constraints enforce one observation per scan/location, one work item per job/task identity, one active hash pointer per observation/algorithm, and idempotent imports/requests.

### 12.2 Required indexes and migration practices

Index at minimum: `(scan_id, entry_type, size_bytes)`, `(scan_id, location_id)`, `(source_id, source_instance_id, parent_id, name_bytes)` for location lookup, parent IDs for tree browsing, mtime filtering, accepted algorithm/digest, signature algorithm/size/digest and enabled revision, work queue state/next-eligible/lease expiry, tag associations, group members, and content `tsvector` with GIN. Add `pg_trgm` indexes only for demonstrated substring-search needs and document the migration.

No unbounded `OFFSET` for million-row pagination. Use keyset pagination with a deterministic tie-break ID and a captured scan/analysis/evidence revision. Expensive exact counts may be asynchronous; label estimated or pending counts. Do not keep long idle HTTP transactions open while the user pages results.

Use Flyway migrations from an empty PostgreSQL instance. Never rely on Hibernate automatic schema creation/update. Test database constraints and queue semantics against PostgreSQL, not H2. Schema evolution must preserve historical scans, signatures, notes, and exported-plan evidence.

## 13. Space analysis and review plans

### 13.1 Separate paths, objects, and physical storage

A file path is a directory entry. Several paths may refer to one inode through hard links. Distinct files may also share storage extents or be retained by snapshots. Linux file metadata exposes link counts and allocation information; reflinks can share underlying storage, and ZFS snapshots can retain blocks. Therefore, summing apparent file sizes is not a reliable prediction of filesystem free-space change. [R6] [R14] [R15]

Identify confirmed hard-link aliases using a trustworthy filesystem identity plus device/inode identity within the scan/validation window. Do not use inode alone, use the filename as storage identity, or assume device identifiers persist across reboots. Deduplicate repeated bind-mount views separately from actual hard-link directory entries. When identity is unavailable or contradictory, mark it unknown and exclude it from an automatically computed reliable object-level removal estimate.

For a duplicate group whose files each have size `S`, show:

```text
P = number of distinct inventoried paths
U = number of confirmed distinct filesystem objects represented by those paths
pathLogicalBytes = P * S
independentObjectLogicalBytes = U * S
maximumDuplicateCopyLogicalBytes = max(U - 1, 0) * S
```

If `U` is not confidently known, show the path count and uncertainty, but do not silently use `P` as an exact object count. `maximumDuplicateCopyLogicalBytes` is a theoretical content-copy measure before retention/safety restrictions, not “bytes that will be freed.”

A group containing only two names of one hard-linked object has `P=2`, `U=1`, and zero duplicate-copy bytes. Empty-file groups likewise provide zero content-byte savings even though they contain multiple entries.

### 13.2 Deduplication review plans

A deduplication plan captures a completed scan, an analysis revision, an explicit scope, keeper preferences, and immutable member references. A scan ending `COMPLETED_WITH_ERRORS` may be used only with a visible partial-coverage acknowledgment; unresolved files are excluded. Default planning rejects unfinished/cancelled inventory, though inventory reports may still export its partial facts.

Default keeper policy is: protect explicit `KEEP` annotations; then prefer an operator-selected source priority; then choose the oldest mtime; then break ties by exact path-byte order. This is a deterministic suggestion, not a claim that the oldest file is the right one. Allow manual overrides. Protected objects with any protected hard-link path cannot become removal candidates.

Dispositions are `KEEP`, `REMOVE_CANDIDATE`, `PROTECTED`, and `EXCLUDED`. In a deduplication plan at least one valid complete object per duplicate group must remain. Attempts to select every object as a removal candidate are rejected transactionally. A candidate cannot be its own keeper. One entry cannot appear twice in the same plan.

For each proposed removable object, require all its observed directory entries to be selected, its observed unique-link count to equal the reported `nlink`, no protected path/keeper references to it, and at least one separate keeper object with valid matching content evidence. If `nlink` exceeds the known unique directory-entry count, links exist outside known scope or identity is uncertain; exclude that object from the automatic recoverable-object estimate. Do not count bind aliases as extra hard links.

Plan metrics:

```text
candidatePathCount
candidateObjectCount
candidateLogicalBytes       # sum S once per eligible removable object
candidateAllocatedBytes     # sum observed allocated bytes once per eligible object,
                            # null/partial when allocation metadata is unavailable
estimatedPhysicalFreedBytes = null
```

Show why actual physical space is unknown: shared extents/reflinks, compression, sparse files, snapshots, filesystem deduplication, unseen links, open handles, and filesystem accounting may affect the result. The UI must not label allocated bytes as guaranteed savings. Prefer “Logical duplicate-copy reduction” and “Observed candidate allocation.”

Use exact integer calculations and display both raw bytes and a clearly labeled human-readable unit. Summation across directories/roots must not count a group/object more than once.

### 13.3 Signature/removal review lists

A separate `FILE_REVIEW` plan allows arbitrary explicitly selected files, including unique files matching signatures. It does not require a duplicate keeper because the intent is review of unwanted content, not deduplication. It still does not perform removal. Use a prominent distinction between this plan type and `DEDUPLICATION_REVIEW` so the duplicate-safety rule is never silently bypassed.

### 13.4 Staleness and validation

Every plan item captures observation ID, size, exact timestamps, identity, hash attempt/digest where available, finding references, decision reason, and keeper identity where relevant. A content-evidence change immediately invalidates dependent current plan eligibility. Annotation/keeper-policy changes set the plan to `NEEDS_REVIEW` when affected; do not silently rewrite an approved selection.

Provide **Validate plan** as a non-mutating read job. `METADATA` mode restats candidates and keepers and reports its limitations; `REHASH` mode explicitly rereads them and verifies checksums. Optional byte verification can be requested for groups. A changed or missing keeper blocks the affected group. Validation results have timestamps and never create a promise that later deletion is safe.

## 14. Safe exports

### 14.1 Export types

Support inventory reports, filtered file lists, duplicate-group reports, signature-findings reports, and review-plan manifests. An export is generated by a durable job and written only to application-owned storage, then downloaded by authenticated artifact ID.

Required file-report formats are **JSON Lines** and **CSV**. Signature-catalog exports additionally use the JSON envelope or CSV schema in section 9.3. Also provide an explicitly chosen **NUL-delimited path list** for external tools that accept it; it is a data file, not a script, and is accompanied by a JSON manifest describing its scope and expected evidence. Do not emit unescaped newline-delimited paths as a reliable machine format.

Never generate `rm`, `find -delete`, shell pipelines, executable scripts, hard-link commands, or an API action that runs the export. No “Apply deduplication” button. Mark all review exports `reviewOnly: true` and `executionSupported: false`.

### 14.2 Frozen selection and provenance

Resolve and persist the selected observation IDs and evidence/plan revisions before asynchronous artifact assembly. Capture annotations and signature metadata as of the export revision. Changes during assembly do not create a mixture of old/new rows. Repeated job execution cannot append duplicate rows. Persist row count and an artifact SHA-256, and publish readiness only after successful complete assembly.

Computing a SHA-256 for the application-owned export artifact is distinct from source-file checksum policy. It must not trigger additional source-file reads.

A JSONL export begins with a `recordType: "manifest"` record, then `recordType: "file"` rows, then a completion record with count and relevant warnings. The download is exposed only when the complete artifact is ready. Include:

- Export/schema version, export ID, creation time, actor, scan ID and completion/coverage status, analysis/evidence/catalog revision, filters, plan type/version, validation mode/time, and source mapping/config revisions.
- For each file: observation/location/source IDs, raw relative-path bytes as base64, safe display path, optional host-path bytes/display, size, exact and display mtime/ctime, filesystem identity/link/allocation metadata, checksum algorithm/value/time/status, duplicate group, finding IDs/names/tags, memo/manual tags, review disposition/reason, and keeper reference when applicable.
- Explicit stale/unhashed/unsupported/partial indicators. An absent checksum is null with a reason, not an empty digest that resembles equality.

### 14.3 Host versus container paths

A container path such as `/sources/archive/photo.jpg` may not exist on the host. Export source ID plus exact relative path as the portable identity. Include a host path only when `hostExportPrefix` was configured and explicitly acknowledged by the operator; this is a mapping claim, not something the container independently verified.

Compose must pass the intended host export prefix into backend configuration when it is used. Do not infer a host path from the container path. Host mappings are versioned and captured in each export. Byte-preserving NUL path exports must join the configured prefix and raw relative bytes without decoding/re-encoding filename bytes or accepting traversal components.

### 14.4 Format safety

CSV must use a real CSV writer with correct quoting of separators, quotes, CR/LF, and UTF-8. Defend against spreadsheet formula injection by neutralizing textual cells beginning with formula/control prefixes, including after leading control characters. Label CSV as spreadsheet-safe display data because neutralization may change display text. Exact filename bytes remain available in base64 columns and in JSONL/NUL formats; an external tool must not treat a neutralized display cell as an exact filename.

JSONL properly escapes control characters and never includes executable markup. NUL files contain exact bytes and separators, with manifest counts. Restrict artifact downloads to opaque IDs, not client-provided filesystem paths. Content-Disposition filenames are generated by the application and cannot contain CR/LF.

### 14.5 Instructions embedded in every review manifest

Include a clear warning equivalent to:

> This manifest is descriptive and does not perform deletion. Files may have changed since scanning or validation. Before any external destructive action, independently resolve the authorized source, revalidate each candidate and its keeper, confirm size and full checksum, and apply your own backup and retention policy. Matching content alone does not establish that a path is unnecessary.

External execution and its implementation are outside this repository. A report from a partial scan includes coverage warnings and must never be relabeled as a completed filesystem assessment.

## 15. Angular user interface

Use Angular standalone components, routing, reactive forms, Angular Material/CDK, and typed API models. This is a desktop-friendly responsive investigative UI, not a mobile PWA requirement. English labels/messages only. Accessibility includes keyboard navigation, properly labeled forms, visible focus, non-color-only status indications, and accessible progress announcements.

### 15.1 Main navigation

| View | Required behavior |
|---|---|
| Dashboard | Active jobs, scan history, configured source availability, duplicate/finding summary with scan context, and explicitly qualified space metrics. |
| New scan | Select registered sources; name scan; show immutable read-only policy; default duplicate-only hashing; explicit signature-candidate and text-indexing toggles with read-cost/privacy explanation. |
| Scan details | Phase/status/counters/errors, pause/resume/cancel controls, current file, heartbeat/I/O status, per-root coverage, committed partial results, and child jobs. |
| File explorer | Lazy-loaded database-backed directory tree and paged file table, breadcrumbs, selected scan, sorting/filtering, hidden files, symlink/special-file icons, and coverage badges. Browsing never triggers recursive rescanning. |
| File details | Stored metadata, checksum and computation time, evidence validity, duplicate members, manual memo/tags, derived signature findings, review state, text-index status, and supported escaped snippets. |
| Search | Metadata and content queries, combined filters, saved-in-view filter state, server pagination, result counts/coverage, and bulk selection. |
| Duplicates | Group list ordered by logical duplicate-copy bytes or count, group detail, hard-link distinctions, keeper suggestions, byte-verify and plan actions. |
| Signatures | Catalog list/edit/enable/disable, create from observation, dry-run/apply import, export, catalog revision and signature-check coverage. |
| Review plans | Plan type, scope, protected keepers, candidate/excluded files and reasons, exact metrics, stale/validation status, and manifest export. No execute/delete action. |
| Jobs and exports | Durable job history, active states, errors/retry options, ready download links, partial/unavailable artifacts never offered as complete. |
| Settings | Read-only source registry/configuration view, resource limits, content-format limits, auth/session information, and software version. Host mounts are changed outside the UI. |

### 15.2 Interaction requirements

Cancel requires confirmation explaining that unfinished work will stop and committed results remain. Pause is reversible and does not require a destructive-operation warning. Resume is disabled when source/config validation fails, with the reason shown. Finishing a scan in one browser updates other views on polling without losing edits.

Bulk selection distinguishes “selected page” from “all matching results.” The server freezes the latter before actions. Forms show optimistic-lock conflicts rather than discarding edits. A scan-root unavailable error does not erase the directory tree of a historical scan.

Checksum fields distinguish “not requested,” “queued,” “calculating,” “calculated at …,” “changed,” and “failed.” Signature and content results show coverage. No green “clean/safe” badge merely because no signature was matched. All action labels refer to identification, investigation, calculation, marking, and export.

## 16. REST API contract

All application routes are under `/api/v1`. Publish an OpenAPI document and generate or validate the Angular DTOs against it. GET operations read application metadata only unless explicitly documented as retrieving an already-generated artifact. Source filesystem work is scheduled through POST jobs, not performed invisibly by GET.

### 16.1 Endpoint inventory

| Method and route | Purpose |
|---|---|
| `GET /session`, `POST /session/login`, `POST /session/logout` | Single-operator authentication/session and CSRF integration. |
| `GET /sources` | Configured sources, read-only/availability/capability status. No arbitrary mount/write API. |
| `POST /scans` | Create/queue scan; return scan ID and job ID. |
| `GET /scans`, `GET /scans/{scanId}` | Paged history and scan/config/coverage details. |
| `GET /jobs`, `GET /jobs/{jobId}` | Job history, state and progress snapshot. |
| `POST /jobs/{jobId}/pause` | Persist pause request. |
| `POST /jobs/{jobId}/resume` | Validate and resume paused/interrupted work. |
| `POST /jobs/{jobId}/cancel` | Persist cancellation. |
| `GET /jobs/{jobId}/errors` | Paged structured errors. |
| `GET /scans/{scanId}/directories/{locationId}/children` | Lazy database-backed tree/table page. |
| `POST /scans/{scanId}/files/search` | Structured filter query with cursor pagination. |
| `GET /observations/{entryId}` | File detail and evidence; requires scan-aware authorization/context. |
| `GET /locations/{locationId}/history` | Paged observation history, never a duplicate group. |
| `POST /hash-jobs` | Explicit selected observations/selection token and force option. |
| `POST /signature-check-jobs` | Catalog revision/scope; explicit candidate hashing consent. |
| `POST /content-index-jobs` | Explicit selected scope/options; durable indexing job. |
| `GET /scans/{scanId}/duplicate-groups` | Paged groups for a published revision. |
| `GET /duplicate-groups/{groupId}` | Group detail and members/evidence. |
| `POST /duplicate-groups/{groupId}/verification-jobs` | Manual byte comparison. |
| `GET /locations/{locationId}/annotation` | Memo/manual tags/review state/version. |
| `PUT /locations/{locationId}/annotation` | Replace annotation using expected version; application DB only. |
| `GET /tags`, `POST /tags`, `PATCH /tags/{tagId}` | Tag catalog management. |
| `POST /annotation-batches` | Frozen bulk annotation/review-state operation. |
| `GET /signatures`, `GET /signatures/{id}` | Catalog list/detail. |
| `POST /signatures`, `PATCH /signatures/{id}` | Create/edit/enable/disable with revisions. |
| `POST /signature-imports` | Upload, validate and stage; no active mutation yet. |
| `GET /signature-imports/{id}` | Dry-run result and errors. |
| `POST /signature-imports/{id}/apply` | Explicit atomic apply with expected catalog revision. |
| `GET /scans/{scanId}/signature-findings` | Matches and coverage for a catalog revision. |
| `POST /review-plans`, `GET /review-plans/{id}` | Freeze analysis/scope/policy and inspect plan. |
| `PATCH /review-plans/{id}` | Versioned decisions/keeper updates with invariants. |
| `POST /review-plans/{id}/validation-jobs` | Explicit metadata/rehash validation. |
| `POST /exports`, `GET /exports/{id}` | Build and inspect immutable export artifact. Includes signature export. |
| `GET /exports/{id}/download` | Authenticated ready artifact only. |
| `GET /system/info`, `GET /system/health` | Minimal safe software/readiness information. |

There are no filesystem DELETE endpoints, file upload-to-source endpoints, source command execution endpoints, or endpoints for applying an export.

### 16.2 Scan request example

```json
{
  "name": "Archive inspection",
  "sourceIds": ["71b53f30-f68f-45d5-a337-763c5a0e0552"],
  "hashAlgorithm": "SHA-256",
  "includeSignatureCandidates": false,
  "textIndexingEnabled": false
}
```

The backend resolves mounted paths and active config, not the client. Unknown source IDs, duplicate/overlapping sources, unsupported algorithms, or unsupported options return validation errors. HTTP 202 means queued, not finished.

### 16.3 File-query contract

Support AND-combined optional filters for filename/path literal contains, exact filename, extension, source, directory subtree, entry type, minimum/maximum byte size, modified-time range, exact hash or explicitly requested prefix, hash status, duplicate status/group, signature ID/tag/status, manual/effective tags, review state, memo text, content token query, content substring query, indexing state, and error/stale state.

Size bounds are inclusive decimal byte strings. Date `from` is inclusive and `to` is exclusive, expressed as UTC ISO 8601 instants; a day chosen in the UI is converted using the user's display timezone and the UI shows it. Filesystem identity never depends on the browser timezone. Filter text is treated literally unless the specific full-text operator is selected. SQL `%`/`_` in a literal contains term are escaped as data.

Every request selects one scan. Default content/hash matching excludes stale evidence. An optional historical/stale toggle displays evidence as stale but never makes it plan-eligible. Tags support explicit `ANY` or `ALL`; distinguish manual from derived/effective tags.

Sorting supports name, path, size, mtime, checksum completion time, and relevant result metrics. Tie-break by a stable ID. Default page size 100, maximum 500. Cursor tokens capture filter hash and input revision; changed scope/revision yields a documented `CURSOR_STALE` 409 or an explicit stable-snapshot response, not silent row skipping.

### 16.4 Mutation and error rules

Support `Idempotency-Key` for job/scan/export/plan/import-apply creation. Store key, actor, endpoint, payload hash, and original response for at least 24 hours. A repeated key with a different payload is rejected. Expected-version conflicts return 409; missing/invalid authorization returns 401/403; input errors 400/422; resource missing 404; capacity/rate limits 429. Do not return HTTP 200 for a failed filesystem job merely because a controller did not throw.

Use structured problem responses with stable error code, readable English detail, correlation ID, optional field errors, and retriable status. Keep stack traces, secrets, and unnecessary host paths out of client messages. Return job-local file errors in the job detail, not as a browser-fatal error that hides other results.

Evidence mutations increment a per-scan evidence revision under a short lock. Grouping captures exact input hash-attempt IDs and builds a generation; it does not join a continuously changing “latest hash” set. Invalidations immediately mark dependent current results stale even while a replacement generation is being built. Export and plan materialization copy/pin their inputs before releasing the capture transaction.

## 17. Docker configuration and deployment

### 17.1 Services and storage

Provide a base Compose stack with backend, frontend, PostgreSQL, health checks, project-scoped networks, database volume, and application artifact volume. Do not hardcode global container names or network names that collide with another stack. Support `COMPOSE_PROJECT_NAME` and configurable host port.

Publish only the frontend by default on `127.0.0.1:8088`. Backend and PostgreSQL remain on internal networks. Remote exposure is an explicit operator decision requiring TLS and authentication. Separate the database-only network from the frontend/backend network where practical.

Backend/frontend containers run non-root, with a read-only root filesystem, dropped capabilities, `no-new-privileges`, bounded process/memory limits, and application-owned temporary locations. The backend has write access only to its own artifact/scratch volume and required temporary directories. PostgreSQL writes only to its dedicated data volume using the chosen image's documented data-directory layout. Do not copy data into scanned sources or use them for application scratch storage.

Source ACLs/ownership are configured by the host operator; the application does not chmod/chown them. Permit configured supplementary read groups. No privileged mode, Docker socket, host PID namespace, host network, device passthrough, or automatic SELinux relabeling of source data. Verify source paths already exist using Compose long bind syntax with `create_host_path: false`; short bind syntax can otherwise create a missing host path. [R16]

### 17.2 Source-mount example

The following is a source override excerpt, not a complete standalone Compose deployment:

```yaml
services:
  backend:
    environment:
      SPRING_CONFIG_ADDITIONAL_LOCATION: file:/config/application.yml
      FNORD_HOST_EXPORT_ARCHIVE: ${FNORD_SOURCE_ARCHIVE:?Set the existing absolute host directory}
    volumes:
      - type: bind
        source: ${FNORD_SOURCE_ARCHIVE:?Set the existing absolute host directory}
        target: /sources/archive
        read_only: true
        bind:
          create_host_path: false
          propagation: rprivate
      - type: bind
        source: ./application.yml
        target: /config/application.yml
        read_only: true
        bind:
          create_host_path: false
```

Backend configuration excerpt:

```yaml
fnord:
  sources:
    - id: "71b53f30-f68f-45d5-a337-763c5a0e0552"
      source-instance-id: "6842060a-5ab7-4880-b19a-0589174fe877"
      key: archive
      label: Archive
      container-path: /sources/archive
      host-export-prefix: "${FNORD_HOST_EXPORT_ARCHIVE}"
      enabled: true
      cross-mounts: false
  scan:
    hash-algorithm: SHA-256
    include-signature-candidates: false
    text-indexing-enabled: false
    discovery-workers: 1
    hash-workers: 2
    metadata-batch-size: 500
    read-buffer-bytes: 4194304
    max-source-readers: 2
  jobs:
    max-filesystem-jobs: 1
    auto-resume-interrupted: false
  content:
    max-file-bytes: 10485760
    max-extracted-utf8-bytes: 262144
```

Bind-mount configuration alone does not discover a root in the UI: each new mount also needs its registry entry. Document how to add any number of sources by repeating both declarations. Config identity remains stable across normal deployments; use a new source instance for a different dataset.

Do not add an invented Compose `bind-recursive` key. Docker's CLI documents recursive-read-only options, while the Compose long-bind schema exposes its own supported fields. Validate the effective mount table at runtime, including any explicitly included nested mounts, rather than assuming an unsupported option works. [R5] [R16]

### 17.3 Secrets, startup, and operations

Use secret files/config-tree integration for database credentials and the operator password hash. `.env.example` contains placeholders, not production secrets. Git-ignore local secret/config files that include private paths or credentials. The application refuses insecure placeholder credentials outside an explicitly isolated test configuration.

Startup performs configuration validation, database connectivity/migrations, scheduler ownership, and root capability validation. Unavailable roots do not prevent browsing old scans; affected new scans are blocked and the source health is degraded. Database failure prevents scheduling/committing work; workers stop rather than dropping metadata on the floor.

Document complete first-run commands, adding roots, UID/GID/ACL requirements, login bootstrap, stop/restart/resume, database backup/restore, artifact retention, image upgrade, and rollback constraints. Provide a preflight script that only inspects real sources. Destructive tests operate on generated fixture directories, never operator data.

Use JSON logs to stdout with bounded rotation in Compose. Include job IDs, scan IDs, state changes, throughput, error codes, and correlation IDs. Default logs should use observation IDs rather than raw contents, memos, or full private paths. Include Spring health and low-cardinality operational metrics; do not use each filename as a metrics label. An external Grafana/ELK stack is not required for v1.

## 18. Security and privacy

Authentication is enabled by default even on localhost. v1 has a single configured operator account with a strong password hash using Spring Security's supported password encoders. No public registration. Use server-side sessions; a restart may require login again but does not lose scan jobs. Rate-limit login attempts and expensive job creation.

Use HttpOnly session cookies, SameSite restrictions, and Secure cookies when served over HTTPS. Keep Spring Security CSRF protection for state-changing browser requests. Angular's readable CSRF-token cookie is separate from the HttpOnly session cookie. Same-origin proxying avoids a permissive cross-origin API. No wildcard credentialed CORS.

Escape filenames, memos, tags, signature labels, and text excerpts. Apply a restrictive content-security policy. Do not inject user data through `innerHTML` or embed source files as active documents. Import validation has byte/row/string limits. Query parameters and sort keys are allowlisted; database access is parameterized.

The source filesystem, catalog labels, extracted text, imports, and exports are untrusted input. They are never instructions to the application or to an AI. Do not execute embedded code, follow content URLs, or load parser resources from the network. The application makes no runtime cloud/API calls and uses no remote fonts/analytics resources. Dependency/image retrieval is a build/deployment concern only.

Memos, filenames, signatures, and extracted text may themselves be sensitive. Restrict database/backups/export access; document operator responsibility for encryption at rest and backup protection. Avoid storing file contents except explicitly enabled bounded text extraction. Audit login/security-relevant events, scan control, explicit content/hash authorization, memo/tag changes, signature changes/imports, plan edits, and exports. Audit retention must not silently purge user evidence in v1.

The no-write guarantee applies to the application's behavior and read-only container view. It does not promise protection against an administrator who remounts sources writable, compromises the host kernel, or supplies inconsistent storage. OS-level read side effects on remote storage and external writers are outside a claim of forensic bit-for-bit media immutability.

## 19. Performance, defaults, and operational limits

Design for millions of file observations and multi-terabyte source files without memory use proportional to file size or the entire tree. Bounded iteration, batches, pagination, and database aggregation are mandatory. No unbounded Groovy `collect`, `groupBy`, or `findAll` over an entire catalog in application memory.

Default concurrency is deliberately modest: one discovery worker, two hash readers, one content-index worker, and a global source-reader cap of two. Reader throttling is configurable per source/root, with an optional aggregate bytes-per-second cap. Separate requests do not bypass the cap. Metadata search and job control must remain responsive during hashing.

Suggested development acceptance environment: 8 CPU cores, 16 GiB RAM, SSD-backed PostgreSQL, backend heap capped at 2 GiB. These are test parameters, not certified minimum hardware requirements. Run correctness integration tests on real fixture trees, a database-scale test of at least 1 million observations, and an optional 10-million-observation benchmark profile. Record dataset shape, hardware, indexes, query plans, warm/cold conditions, and measurements.

Target paged selective metadata queries and normal job-control responses at p95 below two seconds in the documented warmed test environment. Do not promise a fixed scan throughput: filesystem latency, disk layout, device contention, and the candidate-size distribution dominate it. All-size-identical inputs may legitimately require hashing every regular file; the UI must show the estimated read cost.

Enforce configurable limits for query length, page size, import bytes/rows, text extraction, open file handles, queued manual requests, and export disk usage. A limit produces an explicit error or skip status; never silently truncate an inventory or export. Refuse a new export when storage quota is insufficient without touching sources or corrupting an existing ready artifact.

## 20. Delivery sequence and completion criteria

Follow `IMPLEMENTATION_PLAN.md`; map tests to `ACCEPTANCE_TESTS.md`. Finish one significant milestone with runnable results and verification evidence before moving to the next when instructed to stop at milestones.

Required final delivery includes backend/frontend source, locked builds, Dockerfiles and Compose, documented configuration/secrets, PostgreSQL migrations, OpenAPI, unit/integration/browser tests, generated benign fixtures, operations documentation, and `VERIFICATION.md` stating exact commands/results and untested environments.

v1 is complete only when all mandatory acceptance cases pass, source data is unchanged through normal/error/cancel/restart paths, default unique-size files are not hashed, known-signature coverage is honest, manual checks work, metadata and supported text searches work, history/notes survive rescans, plans account for hard links and uncertainty, exports round-trip adversarial filenames, and the full stack survives restart without losing durable work.

Do not substitute a demo UI, mock scan results, in-memory-only jobs, a filename-only “signature matcher,” or a filesystem listing script for the required system. Do not claim performance, Docker, browser, native-filesystem, or crash-recovery validation that has not actually been run.

## 21. Primary technical references

References were consulted September 28, 2026. They establish external platform behavior; product defaults and requirements in this specification are design decisions. No substantial verbatim source excerpts are included.

- **[R1] NIST — SHA-1 retirement and SHA-2/SHA-3 migration:** `https://www.nist.gov/news-events/news/2022/12/nist-retires-sha-1-cryptographic-algorithm`
- **[R2] Spring Boot — System requirements:** `https://docs.spring.io/spring-boot/system-requirements.html`
- **[R3] Spring Boot — Managed dependency coordinates:** `https://docs.spring.io/spring-boot/appendix/dependency-versions/coordinates.html`
- **[R4] Angular — Version compatibility:** `https://angular.dev/reference/versions`
- **[R5] Docker — Bind mounts and recursive read-only behavior:** `https://docs.docker.com/engine/storage/bind-mounts/`
- **[R6] Linux man-pages — stat:** `https://man7.org/linux/man-pages/man2/stat.2.html`
- **[R7] PostgreSQL 18 — Date/time types:** `https://www.postgresql.org/docs/18/datatype-datetime.html`
- **[R8] Linux man-pages — openat2:** `https://man7.org/linux/man-pages/man2/openat2.2.html`
- **[R9] Linux man-pages — statx, including descriptor-relative metadata:** `https://man7.org/linux/man-pages/man2/statx.2.html`
- **[R10] Java 21 — SecureDirectoryStream:** `https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/file/SecureDirectoryStream.html`
- **[R11] PostgreSQL 18 — SELECT locking and SKIP LOCKED:** `https://www.postgresql.org/docs/18/sql-select.html`
- **[R12] PostgreSQL 18 — Full-text search introduction:** `https://www.postgresql.org/docs/18/textsearch-intro.html`
- **[R13] PostgreSQL 18 — Numeric types:** `https://www.postgresql.org/docs/18/datatype-numeric.html`
- **[R14] Linux man-pages — Shared extents through reflink cloning:** `https://man7.org/linux/man-pages/man2/ioctl_ficlonerange.2.html`
- **[R15] OpenZFS — Concepts, including snapshots:** `https://openzfs.github.io/openzfs-docs/man/master/7/zfsconcepts.7.html`
- **[R16] Docker Compose — Services and long bind-mount syntax:** `https://docs.docker.com/reference/compose-file/services/`
