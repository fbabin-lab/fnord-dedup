# ADR 0003 — Scan-scoped hash evidence and immutable analysis

Status: Implemented for M2 draft. Native PostgreSQL concurrency/durability and Docker runtime acceptance remain open.

## Job and candidate lifecycle

V3 retains legacy INVENTORY jobs and replaces their scan uniqueness with a partial unique index for the original INVENTORY/SCAN job. Follow-up HASH jobs reference that same scan. Existing scans keep their metadata-only options. New SCAN jobs freeze inventory, select repeated sizes, hash candidates, and build analysis. One scheduler/active job is still the production concurrency limit.

SELECT_CANDIDATES, HASH and GROUP extend the durable work queue. Database stages use the captured root location as their work identity and perform no source I/O. Candidate-size aggregation uses the scan/size index and at most 500 result rows per transaction. Eligible entry selection creates at most 500 idempotent tasks per transaction, ordered by immutable entry sequence. Only observed, stable regular files with known sizes qualify. The entire scan scope, including selected roots, shares a candidate set; different scans never do. A sole empty file remains unhashed.

Manual jobs freeze 1–500 supplied observation IDs in one scan, with MANUAL and optional FORCED_RECHECK reasons. They use the existing global admission lock, idempotency records, audit events and limits. Source paths are never accepted as API arguments. Candidate counts/bytes become known after selection; discovery totals remain unknown.

## Reads and attempts

The scheduler reuses the M1 coordinator/job/work lock order, epochs, heartbeat and explicit interruption recovery. Native hashing has one 1 MiB buffer and no long filesystem transaction. It opens the captured raw-byte location through the validated root adapter, compares descriptor metadata to the original observation, reads through EOF, validates byte count, post-read metadata, directory entry and root binding, then closes every handle before accepting the result. Each hard-link path is read separately.

Hashing is atomic at the file-result level. The worker keeps the SHA-256 state and byte count only in memory while reading; it does not create a READ/PROGRESS hash_attempt and does not persist per-chunk byte counters. If pause, cancellation, process loss or a read failure interrupts the stream, no hash-attempt row is created and a later resume retries that file from byte zero. After EOF, metadata/path/root validation and a final work fence, one transaction inserts the completed attempt and accepted pointer. The completed row stores the final byte count, reasons, actual start/completion times, pre/post fingerprints and digest when accepted. A fully-read conflicting forced recheck may store a completed CONFLICT attempt without a digest so prior evidence can be invalidated explicitly.

Completion and work settlement share one transaction after handle closure, avoiding a crash gap that could redo an already completed forced task. Incomplete source reads remain represented by durable job/work error or control state rather than pseudo hash evidence. Reuse settles a work item without creating a new attempt or modifying the original checksum time. Finished attempts are immutable. A forced accepted fresh read can replace the active pointer only if its digest agrees; the newly computed attempt retains its own actual completion time. A disagreement appends HASH_CONFLICT validation and invalidates the pointer without storing the new conflicting digest. Original evidence remains for investigation. Source/read failures conservatively invalidate a prior active result.

Accepted hashes are scan-scoped pointers to exact attempts. Every acceptance/invalidation holds the scan row briefly and increments its evidence revision. A detected captured configuration/root mismatch adds a scan-wide evidence-block marker and increments that revision in O(1), so all affected historical results become visibly stale without a tree-wide update. Worker-initiated markers also require the work fence. Restoring a binding cannot silently reactivate invalidated evidence; a new scan establishes a new observation window.

## Analysis construction and publication

A GROUP task stores its stage and keyset cursor in PostgreSQL. It creates a BUILDING analysis_revision with the current evidence revision, then captures exact accepted attempt IDs/digests in analysis_input, at most 500 per transaction. It aggregates up to 50 duplicate fingerprints per transaction and assigns group membership in batches of 500. Names/extensions play no part. Captured inputs also serve as normalized members after group_id is assigned; composite foreign keys enforce matching scan, analysis, algorithm, size and digest. An additional digest-bearing attempt foreign key prevents a captured digest from differing from its referenced accepted attempt.

Each stage checks the captured evidence revision under the scan row lock. Any change abandons the unfinished generation and starts another capture. Publication locks the same row, verifies the revision and updates current_analysis_id atomically only after the member stage ends. Cancelling or losing a process during construction leaves the previous pointer untouched. Abandoned and published revisions remain historical. Every group query independently checks its captured pointers and invalidation markers, so invalidated groups are stale before a rebuild publishes.

A group requires at least two locations. HASH_IDENTICAL denotes matching SHA-256 evidence, never byte-for-byte verification. Members include their exact accepted timestamps. Group/member pages use revision-scoped sequence cutoffs; passing a cursor to another revision is rejected.

## Object identity and arithmetic

Known local filesystem types with consistent device/inode/mount, exact metadata and link counts can supply a confirmed object count within this scan window. Multiple paths to one such object are reported as hard links. Repeated device/inode through distinct mount IDs is a separate uncertain alias classification. Unsupported, absent or contradictory metadata makes the object count unknown. No per-path count is substituted for an unknown object count.

All aggregate byte calculations use NUMERIC(38,0)/BigInteger and JSON decimal strings. The path measure is P*S; confirmed object bytes are U*S; theoretical duplicate-copy bytes are max(U-1,0)*S. A hard-link-only group has zero duplicate-copy bytes. Physical savings are always unknown. Detailed allocation/link accounting, byte verification and review plans remain M6.

No new runtime library or source operation was introduced. Angular types are generated from the maintained OpenAPI contract. API/UI source GETs read stored data only. Native mutation tests operate exclusively on newly generated disposable fixtures; the production read-only mount guard has no bypass flag.
