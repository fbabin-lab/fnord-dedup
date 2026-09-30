# Implemented API — M4

`openapi.yaml` is the contract for implemented routes. The full v1 endpoint inventory in `SPECIFICATION.md` is a future delivery requirement, not a list of currently available routes.

All application URLs are under `/api/v1`. The frontend uses relative same-origin URLs through Nginx. No CORS allowance is configured. The generated Angular schema is `frontend/src/app/api-schema.ts`; regenerate it using `npm run api:generate` from `frontend`.

| Method | Route | Behavior |
|---|---|---|
| GET | `/session` | Anonymous or authenticated session state; issues separate readable XSRF cookie. |
| POST | `/session/login` | Form-encoded username/password plus CSRF header/cookie; 204 on success, 401 on invalid credentials, 403 for CSRF failure, 429 for throttling. |
| POST | `/session/logout` | Requires CSRF; destroys the session and cookies, returns 204. |
| GET | `/sources` | Authenticated configured source list and configuration revision; cached startup/job validation, no source reads. |
| GET | `/system/info` | Authenticated software/capability information; `scanAvailable=true`, `inventoryOnly=false`. |
| GET | `/system/health` | Public minimal application/database readiness; 200 UP or 503 DOWN. |

| POST | `/scans` | Queue inventory, repeated-size hashes and analysis; 202 with scan ID, job ID and `inventoryOnly=false`. Supports Idempotency-Key. |
| GET | `/scans`, `/scans/{scanId}` | Paged scan history and captured scope, coverage, inventory freeze time and job progress. |
| GET | `/jobs`, `/jobs/{jobId}` | Paged jobs and durable progress; discovery totals are unknown. |
| POST | `/jobs/{jobId}/pause`, `/resume`, `/cancel` | Persist cooperative controls. Resume validates source snapshots and identities. |
| GET | `/jobs/{jobId}/errors` | Paged structured errors, scoped to the job. |
| GET | `/scans/{scanId}/directories/{locationId}/children` | Committed observations only; does not access source files. |
| GET | `/observations/{entryId}` | Original metadata, exact byte identity, instability and hash evidence. |

`GET /actuator/health` and probe subpaths are internal Spring health endpoints; Nginx does not proxy `/actuator`. No database detail is returned. Runtime `/api` source-download, arbitrary-file, mutation and execution routes do not exist.

## Browser session sequence

1. GET `/session` to load state and the `XSRF-TOKEN` cookie.
2. POST form data to `/session/login` with `X-XSRF-TOKEN` equal to that cookie.
3. GET `/session` again to refresh the token after authentication; Spring clears the pre-login token.
4. Send the same header on state-changing requests. Angular's normal XSRF support handles relative URLs.
5. POST `/session/logout`, then GET `/session` to obtain a new anonymous token.

The session cookie is HttpOnly and SameSite=Strict. The XSRF cookie is readable and SameSite=Strict. Set `FNORD_SECURE_COOKIES=true` for HTTPS; HTTP localhost uses false. Login is limited globally to five processed attempts per minute for this single-operator application. The limit is process-local and resets on restart. This is not a distributed brute-force or DDoS protection service.

Authentication/CSRF errors use the problem schema with a generated correlation ID; caller-supplied correlation values are not trusted. Source validation errors are records in `/sources`, so an unavailable root does not prevent login or database browsing. Cached status does not certify a later mount state; the scheduler revalidates before traversal, and explicit resume revalidates before requeueing.

## Contract tooling

Angular requires TypeScript 6, while the pinned OpenAPI generator declares a TypeScript 5 peer dependency. Generator dependencies are isolated under `tools/api-codegen` with their own lockfile. Both installations use `npm ci`; no peer checks are disabled. `npm run api:check` detects a stale committed DTO schema.


## Inventory contract

Scan requests accept a name and distinct registered source UUIDs. The only algorithm identifier is `SHA-256`; scans hash every eligible path whose size occurs at least twice in the frozen scan, including each hard-link path. `includeSignatureCandidates` defaults false; true adds enabled signature sizes from the catalog captured at scan creation. `unsafeFast` also defaults false; when true it bypasses the normal source-safety/consistency checks for this scan. `textIndexingEnabled` must still be false. Unknown options and arbitrary paths are rejected. New scan jobs have type `SCAN` and advance through `INVENTORY`, `CANDIDATE_SELECTION`, `HASHING`, `ANALYSIS` and `SIGNATURE_MATCH`. Historical M1 jobs retain type `INVENTORY` and their metadata-only options. `analysisId` identifies a published revision; completion with errors is not complete evidence for every observed file. `job` is the original scan job, while `latestJob` also includes follow-up manual hashing. `activeHashJobs` exposes all unfinished follow-ups (bounded by the ten-job admission limit), so a newer request does not hide an older paused job.

An optional `Idempotency-Key` contains 1–128 printable ASCII characters. The server records the actor, endpoint, normalized payload hash and original response, currently indefinitely (at least 24 hours). Source order and omitted default options do not change normalized identity. The same key/body returns the original 202 response; a changed body returns 409. Replay still works when admission capacity is exhausted. There are at most ten unfinished jobs and five accepted new scan/hash/signature-check requests per actor per minute; limits return 429.

Pause moves QUEUED directly to PAUSED or RUNNING to PAUSE_REQUESTED. Cancellation moves queued/paused/interrupted jobs directly to CANCELLED; running or pause-requested work becomes CANCEL_REQUESTED until its handles close. Cancel wins a pause race. Terminal jobs cannot resume; completed jobs reject late cancellation. Resume normally performs source validation outside a DB transaction, then compares the locked job version before requeueing. `POST /jobs/{id}/resume` may include `{unsafeFast:true}` to permanently promote that scan to unsafe fast mode for its remaining work; this skips resume validation and cannot be reverted for that scan. Configuration or identity mismatches return 409 with a specific code. Database failures return a structured 503; a caller should reuse its creation key or reload durable state.

Inventory/history pages use `limit` (default 100 except hash attempts: 20 and groups: 50; maximum 500) and an opaque `cursor`. Rows are ordered by increasing database sequence. The initial page captures a committed sequence cutoff; subsequent pages exclude later inserts. Cursor scope includes the list kind and scan/directory or job context. A different scope returns `CURSOR_STALE` (409); malformed cursors return 422. Refresh without a cursor to include newly committed rows. This is a cutoff for inventory identity, not a frozen snapshot of mutable job progress or appended instability evidence.

Individual sizes, aggregate bytes, counters, large sequence IDs and inode/mount identifiers are JSON decimal strings. Timestamps include readable UTC values and exact native epoch seconds plus nanoseconds. Missing optional metadata is null; `metadataMask` records native statx capabilities. `ctime` is metadata-change time, not creation time. Raw relative paths and names are base64; display strings never serve as filesystem identity. The first observation for a scan/location is retained when replay differs, with `unstable=true` from appended validation evidence. Directory coverage remains PARTIAL until enumeration and all descendant directory work settle; inaccessible descendants propagate partial coverage to their ancestors.

## Hash and duplicate evidence

| Method | Route | Behavior |
|---|---|---|
| POST | `/hash-jobs` | `scanId`, 1–500 distinct `observationIds` or one `selectionId`, optional boolean `forceRehash` (false). Requires frozen inventory and eligible regular observations from that scan. Same idempotency/admission/CSRF rules as scan creation. Returns 202 with job/scan IDs. |
| GET | `/observations/{id}/hash-attempts` | Paged immutable completed attempts only. In-progress, interrupted, cancelled, or read-failed hashing creates no attempt row. Completed rows include reasons, final byte count, timestamps, available pre/post fingerprints, outcome and accepted digest when valid. |
| GET | `/scans/{id}/duplicate-groups` | Current published analysis, or a historical published `analysisId`; paged groups. Pin `analysisId` while paging. Revision mismatch returns 409. Unpublished builds are unavailable. |
| GET | `/duplicate-groups/{id}` | Group plus paged members with exact captured attempt IDs and checksum timestamps. |

`hash.status` distinguishes `NOT_REQUESTED_UNIQUE_SIZE`, `NOT_REQUESTED`, `PENDING`, `ACCEPTED`, `FAILED`, `STALE`, and `INELIGIBLE`. Observation lists omit the optional `hash` object; observation detail includes it. A missing hash does not prove that content is unique. A stale result may retain an `accepted` historical attempt for investigation; the status determines whether that evidence remains usable.

Only complete validated attempts store a 32-byte digest; API hex is lowercase and 64 characters. Manual reuse creates no new read attempt and preserves its actual checksum time. A fresh conflict retains the original accepted attempt, stores a conflict without a digest, invalidates current evidence, and excludes that observation until a new scan. An observed captured-configuration/root identity mismatch conservatively invalidates the whole scan evidence window through one marker, preserving historical rows.

Analysis publication is atomic. `CURRENT` / `NEEDS_REBUILD` compares captured and current evidence revisions; each group's `HASH_IDENTICAL` / `STALE` status also checks its exact members immediately. This milestone never reports `BYTE_VERIFIED`. Group membership never crosses scans.

Path bytes and confirmed-object bytes are separate. Hard links are recognized only with consistent supported filesystem/device/inode/mount identities and link metadata. Repeated mount views are `ALIASED_MOUNTS_UNCERTAIN`; unsupported/contradictory identity is `IDENTITY_UNKNOWN`. Both have null object counts/estimates. `physicalSavingsBytes` is always null. Theoretical duplicate-copy bytes are not a promise of reclaimed disk space.

Jobs expose candidate files/bytes, fresh hashes, reused files and completed hashed bytes as decimal strings. Incomplete reads are not persisted as hash progress and do not contribute to completed-byte counters; they restart from byte zero. During a long hash, local shutdown is checked at each read boundary and durable pause/cancel state is checked no more often than every ten seconds plus once before final publication. Source GETs remain database-only.


## Explorer and location annotations

| Method | Route | Behavior |
|---|---|---|
| POST | `/scans/{id}/files/search` | Structured AND filters, stable server sorting and keyset pages. Read-only query; authenticated POST still requires CSRF. |
| GET | `/scans/{scanId}/directories/{locationId}` | Observed directory context and bounded stored breadcrumbs. |
| GET | `/locations/{id}/history` | Paged observation history across scans at one location, never duplicate-copy evidence. |
| GET | `/locations/{id}/annotation` | Full memo, tags, review state, version and needs-review flag; optional `observationId`, otherwise latest observed entry. |
| PUT | `/locations/{id}/annotation` | `observationId`, decimal `expectedVersion`, full `memo`, `tagIds`, `reviewState`. Audited atomic replacement; version starts at 0 and stale writes return 409. |
| GET / POST | `/tags` | Paged literal normalized-label search / create reusable tag. |
| PATCH | `/tags/{id}` | Rename label with decimal `expectedVersion`; conflict or normalized duplicate returns 409. |
| POST | `/scans/{id}/selections` | Persist a frozen selection from `{query, viewToken, observationIds?}`. Omitting IDs means all matching. Reject 0 or >500 rows. |
| GET | `/selections/{id}` | Frozen count, known bytes, unknown-size count, query, expiry and paged members with captured annotations alongside current state. |
| POST | `/annotation-batches` | `{selectionId, addTagIds?, removeTagIds?, reviewState?}`. Atomic single-use batch with optional `Idempotency-Key`. No-op requests are rejected. |

Search accepts `filters`, `sort`, `direction`, `limit` and `cursor`. Defaults are empty filters, path-byte ASC, and 100 rows; max 500. All decimal input bounds are strings within signed 64-bit file-size range. Aggregates use PostgreSQL NUMERIC and JSON decimal strings; `totalBytes` includes known sizes only and `unknownSizes` counts missing values.

| Filter family | Fields / semantics |
|---|---|
| Names and scope | `nameContains`, `pathContains`, `nameExact`, `pathExact`: literal case-sensitive UTF-8 byte comparison. `nameBytesBase64`, `pathBytesBase64`: exact raw bytes. `extension`: exact stored extension. `sourceId`, `subtreeLocationId` (includes its root), `parentLocationId` (immediate children), `entryType`. |
| Size and date | `minBytes` / `maxBytes` inclusive. `mtimeFrom` / `mtimeTo` UTC instants ending Z, `[from,to)`, nanosecond precision. |
| Evidence | Exact 64-hex `checksum` or explicit 1–64-hex `checksumPrefix`, only currently accepted evidence. `hashStatus`, `duplicateState` (`DUPLICATE`, `STALE`, `NOT_GROUPED`), `duplicateGroupId`, boolean `hasError` / `stale`. |
| Notes | `tagIds` with `tagMode=ANY` or `ALL`, literal `memoContains`, `reviewState`, boolean `annotationsNeedReview`. |

Unknown fields, including unimplemented content criteria, return 422. `%`/`_` are never wildcards in text filters. No Unicode normalization or case folding applies to filename/path bytes. Sort keys are `name`, `path`, `size`, exact `mtime`, or `checksumTime`; ASC/DESC use a stable UUID tie-breaker and nulls last.

The initial search captures an entry sequence cutoff, scan evidence/query revisions, current analysis ID and annotation revision. A continuation must match the normalized query and those revisions; otherwise 409 `CURSOR_STALE`. New entries alone remain outside the cutoff. Metadata/status updates, candidate materialization, hash completion, job transitions and annotation changes conservatively expire a view. A global search clock also captures older-scan annotation-baseline changes and is rechecked after each search/freeze read. Unrelated annotation or scan evidence/status updates can therefore expire a cursor conservatively. Cursors are bounded opaque metadata, not authentication credentials. Directory/catalog/history sequence pages retain the earlier cutoff behavior; a catalog rename does not freeze the catalog display.

Selections resolve IDs transactionally from the captured query, retain actor/scan/analysis context, annotation versions/full snapshots, accepted attempt IDs and stale flags, and expire after 24 hours. Their target set never changes. Relevant member annotation/tag versions, active hash evidence, annotation-baseline validity, or analysis changes set `needsReview`; a batch rejects them with `SELECTION_STALE`. New inventory rows alone do not invalidate a frozen batch. Bulk changes preserve prior memo baselines. `KEEP` and other review states are application metadata; M6 will enforce plan protections.

`/hash-jobs` also accepts `selectionId` instead of `observationIds`, with the same `scanId` and eligibility rules. It validates actor, expiry and captured state before scheduling. Idempotent replay happens before revalidation/admission, preserving previously accepted requests, including requests recorded before M3. Annotation batches do not consume the scan/hash creation rate limit.

Tag labels are 1–64 Unicode code points after whitespace trimming. Lookup is Unicode NFKC then Locale.ROOT lowercase, while display preserves the trimmed entered form. Memos are plain Unicode text up to 20,000 code points. Invalid Unicode/NUL is rejected. Saving notes on an observed replacement explicitly reaffirms the annotation baseline; tags/memos never automatically transfer to a new location.

Candidate-size absence is reported as `NOT_REQUESTED_UNIQUE_SIZE` only after candidate materialization finishes. While selection is incomplete, it remains `NOT_REQUESTED`; absence from a partial size set is not evidence of a unique size.


## Signature catalog and coverage

| Method | Route | Behavior |
|---|---|---|
| GET | `/signatures` | Name/memo search; keyset page with immutable `catalogRevision`. Send that revision on later pages. |
| GET | `/signatures/{id}` | Current record or an exact historical `revision`. |
| POST / PATCH | `/signatures`, `/signatures/{id}` | Full metadata/fingerprint fields; edit requires decimal `expectedRevision`. Multiple IDs may share one fingerprint. Tags accept normalized `tags` labels or stable `tagIds`, never both. |
| GET | `/signature-limits` | Configured import byte/row and per-export byte caps. |
| POST | `/scans/{id}/signature-check-preview` | Actor-owned one-hour preview of eligible, previously unhashed signature-size candidates; exact decimal files/bytes, catalog/evidence revision and observation cutoff. No body reads. |
| POST | `/signature-check-jobs` | `{previewId, allowBodyReads:true}` with `Idempotency-Key`. Reject changed/expired previews. Durable candidate selection, hashing, grouping and matching. |
| GET | `/observations/{id}/signatures` | Guarded current coverage and paged derived findings; `runId` selects published history. Each finding carries complete signature metadata, exact revision and match time. |
| GET | `/scans/{id}/signature-findings` | Paged current coverage for saved observations. |
| GET | `/scans/{id}/signature-runs` | Published matching history; obsolete runs remain accessible. |

Size must be an unsigned decimal string no greater than `9223372036854775807`. SHA-256 is the only algorithm; checksum input is exactly 64 hex digits, normalized lowercase. Name is 1–200 Unicode code points and nonblank; memo at most 20,000; source note at most 2,000; at most 100 distinct reusable tags. Filename matching defaults to ADVISORY. REQUIRED_EXACT adds a case-sensitive raw-basename predicate and excludes renamed copies. Optional `filenameBytesBase64` is canonical base64 of one basename (1–255 bytes, no NUL/slash/dot entries) and is authoritative when a lossy display filename accompanies it.

Create from an observation by supplying `observationId`, name and optional labels/memo/mode; omit size, algorithm, checksum and both filename fields. The server requires current accepted evidence and supplies the real fingerprint/raw basename. `HASH_REQUIRED` (409) directs the operator to explicitly hash, wait and continue. Creating a signature never initiates that read.

Coverage carries separate `matchStatus` (MATCHED / NO_MATCH_IN_CHECKED_CATALOG / UNDETERMINED) and `checkStatus` (CHECKED_HASH / EXCLUDED_BY_SIZE / HASH_REQUIRED / STALE / READ_ERROR / CATALOG_NOT_CHECKED). Catalog revision, current-catalog flag, captured accepted attempt and check time accompany it. Negative results apply only to that revision, never mean “safe” or “clean,” and stale hashes are not active matches. Historical runs retain original statuses with `current:false` and `active:false` findings.

Catalog changes advance a durable global revision. The scheduler coalesces pending revisions and queues one database-only scan rematch at a time. A running match finishes its captured catalog; the later revision then gets a new run. Scan/manual hash jobs also match automatically after grouping. Paused/cancelled matching jobs retain their checkpoints/history; resume for the database-only SIGNATURE_MATCH job does not validate/open source mounts. Scan and explicit SIGNATURE_CHECK jobs retain ordinary read-safety controls. Current labels switch atomically at publication, without touching manual notes/tags. Search accepts `signatureId`, `signatureTagId`, `signatureStatus`, `signatureCheckStatus`, and `tagScope=MANUAL` (default) or `EFFECTIVE` with existing `tagIds` ANY/ALL. EFFECTIVE combines manual and currently valid derived tag IDs; signature metadata/tag labels are historical revision snapshots.

## Atomic catalog exchange

`POST /signature-imports?format=JSON|CSV&policy=REJECT_EXISTING_ID|UPDATE_BY_ID` consumes a raw UTF-8 body (not multipart). The default is 1 MiB / 2,000 records, configured through `fnord.signatures`; hard configuration ceilings are 16 MiB / 10,000 records. The entire bounded upload is parsed and validated before staging. JSON rejects duplicate object keys and trailing content. CSV uses Apache Commons CSV 1.14.1 RFC4180; its header must equal:

```csv
schemaVersion,id,revision,name,memo,tagsJson,sizeBytes,algorithm,checksum,filename,filenameBytesBase64,filenameMatchMode,enabled
```

JSON uses `{schemaVersion:1, exportedAt?:"UTC", signatures:[...]}`; records use the specification's schema. Tags are label arrays (`tagsJson` in CSV). Empty IDs generate new UUIDs. The default policy rejects existing IDs. UPDATE_BY_ID requires every row's existing ID and exact expected positive record revision; new records use a separate default-policy import. Empty optional CSV cells use defaults (enabled true, ADVISORY filename, empty memo/tags); decimal sizes never accept numbers/units/exponents in JSON. CSV enabled cells are empty, true or false. Formula-like strings remain inert application metadata. Unknown fields, versions, invalid base64, duplicate record IDs, malformed fingerprints and overflow fail validation. Distinct IDs sharing a fingerprint are retained.

`GET /signature-imports/{id}` returns an actor-owned dry run, row errors/proposed values and paged rows. At most 20 unapplied imports per actor are live during their 24-hour expiry. `POST /signature-imports/{id}/apply` takes `{expectedCatalogRevision}` and `Idempotency-Key`. It locks the catalog, checks the staged base and every record revision, and applies all rows/tags plus audit/idempotency state in one transaction. Any error rolls everything back. Successful retries return the original result; catalog changes require a new dry run. Invalid/expired staging never applies. Staging rows and applied evidence are retained.

`POST /signature-exports` takes `{format:"JSON"|"CSV", catalogRevision}` and `Idempotency-Key`. It creates a durable, actor-owned database artifact job with a reserved per-artifact cap (16 MiB default; 64 MiB hard configuration ceiling) and total reservation quota (256 MiB default). `GET /signature-exports/{id}` reports state, exact row/byte counts and eventual SHA-256. POST suffixes `/pause`, `/resume`, `/cancel` control unfinished jobs. `/download` requires READY and sends an authenticated attachment named only from its artifact UUID. The worker builds 100-record chunks and commits each with its cursor, then hashes all bounded chunks before atomically publishing READY. Interrupted database transactions replay the uncommitted batch; incomplete/cancelled/failed outputs never download. Failed/cancelled chunks are removed from application tables only; ready artifacts and their reserved capacity persist. No arbitrary path is accepted and no source is opened.

JSON preserves exact exported metadata and basename bytes. CSV is spreadsheet-safe display data: formula/control-prefixed textual cells receive a leading apostrophe, including after leading whitespace/control characters. This may change names/memos on reimport; use JSON for exact metadata exchange. Base64 beginning `+` is likewise neutralized in CSV, and the CSV importer removes that single documented marker from the basename-base64 column before strict validation. Other metadata markers are retained as data. CSV's fixed schema has no source-note column; JSON includes sourceNote. All escaping/quoting uses the CSV writer, including CR/LF and quotes. General inventory, findings, path-list and review-plan artifact formats remain M7.

Frozen explorer selections also store their catalog and published signature-run revisions. A catalog edit or replacement finding run conservatively marks an existing selection as needing review; later annotation batches/hash requests reject it with SELECTION_STALE while retaining its original member IDs. This includes filters over derived tags/signatures and prevents confirmation against changed labels.
