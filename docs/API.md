# Implemented API — M1

`openapi.yaml` is the contract for implemented routes. The full v1 endpoint inventory in `SPECIFICATION.md` is a future delivery requirement, not a list of currently available routes.

All application URLs are under `/api/v1`. The frontend uses relative same-origin URLs through Nginx. No CORS allowance is configured. The generated Angular schema is `frontend/src/app/api-schema.ts`; regenerate it using `npm run api:generate` from `frontend`.

| Method | Route | Behavior |
|---|---|---|
| GET | `/session` | Anonymous or authenticated session state; issues separate readable XSRF cookie. |
| POST | `/session/login` | Form-encoded username/password plus CSRF header/cookie; 204 on success, 401 on invalid credentials, 403 for CSRF failure, 429 for throttling. |
| POST | `/session/logout` | Requires CSRF; destroys the session and cookies, returns 204. |
| GET | `/sources` | Authenticated configured source list and configuration revision; cached startup/job validation, no source reads. |
| GET | `/system/info` | Authenticated software/capability information; `scanAvailable` and `inventoryOnly` are true. |
| GET | `/system/health` | Public minimal application/database readiness; 200 UP or 503 DOWN. |

| POST | `/scans` | Queue an inventory; 202 with scan ID, job ID and inventory-only flag. Supports Idempotency-Key. |
| GET | `/scans`, `/scans/{scanId}` | Paged scan history and captured scope, coverage, inventory freeze time and job progress. |
| GET | `/jobs`, `/jobs/{jobId}` | Paged jobs and durable progress; discovery totals are unknown. |
| POST | `/jobs/{jobId}/pause`, `/resume`, `/cancel` | Persist cooperative controls. Resume validates source snapshots and identities. |
| GET | `/jobs/{jobId}/errors` | Paged structured errors, scoped to the job. |
| GET | `/scans/{scanId}/directories/{locationId}/children` | Committed observations only; does not access source files. |
| GET | `/observations/{entryId}` | Original metadata, exact byte identity and instability evidence. |

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

Scan requests accept a name and distinct registered source UUIDs. The only algorithm identifier is `SHA-256`; M1 stores the selection but does not perform hashing. `includeSignatureCandidates` and `textIndexingEnabled` must be false. Unknown options and arbitrary paths are rejected. Responses and the UI explicitly identify this milestone as inventory-only; neither `COMPLETED` nor `COMPLETED_WITH_ERRORS` implies duplicate analysis.

An optional `Idempotency-Key` contains 1–128 printable ASCII characters. The server records the actor, endpoint, normalized payload hash and original response, currently indefinitely (at least 24 hours). Source order and omitted default options do not change normalized identity. The same key/body returns the original 202 response; a changed body returns 409. Replay still works when admission capacity is exhausted. There are at most ten unfinished jobs and five accepted new requests per actor per minute; limits return 429.

Pause moves QUEUED directly to PAUSED or RUNNING to PAUSE_REQUESTED. Cancellation moves queued/paused/interrupted jobs directly to CANCELLED; running or pause-requested work becomes CANCEL_REQUESTED until its handles close. Cancel wins a pause race. Terminal jobs cannot resume; completed jobs reject late cancellation. Resume performs source validation outside a DB transaction, then compares the locked job version before requeueing. Configuration or identity mismatches return 409 with a specific code. Database failures return a structured 503; a caller should reuse its creation key or reload durable state.

All pages use `limit` (default 100, maximum 500) and an opaque `cursor`. Rows are ordered by increasing database sequence. The initial page captures a committed sequence cutoff; subsequent pages exclude later inserts. Cursor scope includes the list kind and scan/directory or job context. A different scope returns `CURSOR_STALE` (409); malformed cursors return 422. Refresh without a cursor to include newly committed rows. This is a cutoff for inventory identity, not a frozen snapshot of mutable job progress or appended instability evidence.

Individual sizes, aggregate bytes, counters, large sequence IDs and inode/mount identifiers are JSON decimal strings. Timestamps include readable UTC values and exact native epoch seconds plus nanoseconds. Missing optional metadata is null; `metadataMask` records native statx capabilities. `ctime` is metadata-change time, not creation time. Raw relative paths and names are base64; display strings never serve as filesystem identity. The first observation for a scan/location is retained when replay differs, with `unstable=true` from appended validation evidence. Directory coverage remains PARTIAL until enumeration and all descendant directory work settle; inaccessible descendants propagate partial coverage to their ancestors.
