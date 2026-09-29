# Implemented API — M2

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

Scan requests accept a name and distinct registered source UUIDs. The only algorithm identifier is `SHA-256`; M2 hashes every eligible path whose size occurs at least twice in the frozen scan, including each hard-link path. `includeSignatureCandidates` and `textIndexingEnabled` must be false. Unknown options and arbitrary paths are rejected. New scan jobs have type `SCAN` and advance through `INVENTORY`, `CANDIDATE_SELECTION`, `HASHING` and `ANALYSIS`. Historical M1 jobs retain type `INVENTORY` and their metadata-only options. `analysisId` identifies a published revision; completion with errors is not complete evidence for every observed file. `job` is the original scan job, while `latestJob` also includes follow-up manual hashing. `activeHashJobs` exposes all unfinished follow-ups (bounded by the ten-job admission limit), so a newer request does not hide an older paused job.

An optional `Idempotency-Key` contains 1–128 printable ASCII characters. The server records the actor, endpoint, normalized payload hash and original response, currently indefinitely (at least 24 hours). Source order and omitted default options do not change normalized identity. The same key/body returns the original 202 response; a changed body returns 409. Replay still works when admission capacity is exhausted. There are at most ten unfinished jobs and five accepted new requests per actor per minute; limits return 429.

Pause moves QUEUED directly to PAUSED or RUNNING to PAUSE_REQUESTED. Cancellation moves queued/paused/interrupted jobs directly to CANCELLED; running or pause-requested work becomes CANCEL_REQUESTED until its handles close. Cancel wins a pause race. Terminal jobs cannot resume; completed jobs reject late cancellation. Resume performs source validation outside a DB transaction, then compares the locked job version before requeueing. Configuration or identity mismatches return 409 with a specific code. Database failures return a structured 503; a caller should reuse its creation key or reload durable state.

All pages use `limit` (default 100 except hash attempts: 20 and groups: 50; maximum 500) and an opaque `cursor`. Rows are ordered by increasing database sequence. The initial page captures a committed sequence cutoff; subsequent pages exclude later inserts. Cursor scope includes the list kind and scan/directory or job context. A different scope returns `CURSOR_STALE` (409); malformed cursors return 422. Refresh without a cursor to include newly committed rows. This is a cutoff for inventory identity, not a frozen snapshot of mutable job progress or appended instability evidence.

Individual sizes, aggregate bytes, counters, large sequence IDs and inode/mount identifiers are JSON decimal strings. Timestamps include readable UTC values and exact native epoch seconds plus nanoseconds. Missing optional metadata is null; `metadataMask` records native statx capabilities. `ctime` is metadata-change time, not creation time. Raw relative paths and names are base64; display strings never serve as filesystem identity. The first observation for a scan/location is retained when replay differs, with `unstable=true` from appended validation evidence. Directory coverage remains PARTIAL until enumeration and all descendant directory work settle; inaccessible descendants propagate partial coverage to their ancestors.

## Hash and duplicate evidence

| Method | Route | Behavior |
|---|---|---|
| POST | `/hash-jobs` | `scanId`, 1–500 distinct `observationIds`, optional boolean `forceRehash` (false). Requires frozen inventory and eligible regular observations from that scan. Same idempotency/admission/CSRF rules as scan creation. Returns 202 with job/scan IDs. |
| GET | `/observations/{id}/hash-attempts` | Paged immutable finished attempts, with one potentially in-progress attempt. Reasons, byte count, timestamps, available pre/post fingerprints, errors and accepted digest. |
| GET | `/scans/{id}/duplicate-groups` | Current published analysis, or a historical published `analysisId`; paged groups. Pin `analysisId` while paging. Revision mismatch returns 409. Unpublished builds are unavailable. |
| GET | `/duplicate-groups/{id}` | Group plus paged members with exact captured attempt IDs and checksum timestamps. |

`hash.status` distinguishes `NOT_REQUESTED_UNIQUE_SIZE`, `NOT_REQUESTED`, `PENDING`, `ACCEPTED`, `FAILED`, `STALE`, and `INELIGIBLE`. Observation lists omit the optional `hash` object; observation detail includes it. A missing hash does not prove that content is unique. A stale result may retain an `accepted` historical attempt for investigation; the status determines whether that evidence remains usable.

Only complete validated attempts store a 32-byte digest; API hex is lowercase and 64 characters. Manual reuse creates no new read attempt and preserves its actual checksum time. A fresh conflict retains the original accepted attempt, stores a conflict without a digest, invalidates current evidence, and excludes that observation until a new scan. An observed captured-configuration/root identity mismatch conservatively invalidates the whole scan evidence window through one marker, preserving historical rows.

Analysis publication is atomic. `CURRENT` / `NEEDS_REBUILD` compares captured and current evidence revisions; each group's `HASH_IDENTICAL` / `STALE` status also checks its exact members immediately. This milestone never reports `BYTE_VERIFIED`. Group membership never crosses scans.

Path bytes and confirmed-object bytes are separate. Hard links are recognized only with consistent supported filesystem/device/inode/mount identities and link metadata. Repeated mount views are `ALIASED_MOUNTS_UNCERTAIN`; unsupported/contradictory identity is `IDENTITY_UNKNOWN`. Both have null object counts/estimates. `physicalSavingsBytes` is always null. Theoretical duplicate-copy bytes are not a promise of reclaimed disk space.

Jobs expose candidate files/bytes, fresh hashes, reused files, physical bytes read and useful completed bytes as decimal strings. Physical bytes include committed discarded/retried chunks; an abrupt process loss may leave the last uncommitted chunk unrecorded. Hash controls are checked at 1 MiB boundaries; unfinished streams restart at zero. Source GETs remain database-only.
