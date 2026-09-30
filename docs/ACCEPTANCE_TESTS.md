# Fnord Dedup — acceptance test matrix

**Applies to:** Specification v1.0.  
**Rule:** Use benign, generated, disposable fixtures. Never run mutation/negative tests against an operator's real source data. Report skipped environment-dependent tests as unverified, not passed.

Tests must examine database state and actual filesystem reads where relevant, not only rendered UI messages. Instrument `ReadOnlyFileAccess` and hash/extractor interfaces in automated tests. Use a real Linux integration suite for behavior mocks cannot prove, and real PostgreSQL for constraints, queue claims, and transactions.

## A. Build and filesystem safety

**AT-01 — Reproducible stack.** From a fresh checkout, locked backend/frontend builds pass, Flyway initializes an empty PostgreSQL database, Compose services become healthy, and the authenticated frontend reaches the real backend. No local globally installed Gradle or Angular CLI is required.

**AT-02 — No source-mutation capability.** Inspect the source-access interface, routes, UI, generated outputs, and deployment. None exposes delete/move/rename/quarantine/relink/write/execute actions on sources. Complete an inventory/hash/index/plan/export cycle and compare fixture bytes, names, mtime, ctime, permissions, and links before/after. Changes from deliberate external-writer tests are assessed separately.

**AT-03 — Writable source rejected.** Mount a generated fixture writable. Source validation blocks scanning without attempting a write probe. In a separate disposable negative test, the correctly deployed read-only scan mount rejects attempted writes. Never perform that negative write test against real source data.

**AT-04 — Submount policy.** A nested mount is recorded but not traversed by default. Explicitly enabled nested traversal accepts read-only submounts and rejects writable ones. Test the actual mount table; do not only inspect YAML. Host mount setup is opt-in and restricted to disposable fixtures.

**AT-05 — Confinement and links.** Include symlinks to inside/outside roots, a symlink directory loop, traversal-looking API inputs, and a directory-replacement race. Symlinks are inventoried but not followed. No bytes are read from outside the authorized root. The API accepts IDs, not an unchecked absolute open path.

**AT-06 — Exact filename identity.** Include spaces, quotes, commas, CR/LF, tabs, leading hyphens, backslashes, decomposed/composed Unicode, and invalid UTF-8 bytes. All entries remain distinguishable and addressable by opaque IDs. Display strings are escaped; raw bytes round-trip.

**AT-07 — Special files and replacement.** Include FIFO/socket entries and simulate replacement of a candidate path with a FIFO/symlink/special file before opening. The scanner does not read their content or hang waiting for a pipe writer, and it records an unsupported/changed result.

**AT-08 — Descriptor consistency.** Replace a pathname between preliminary metadata and open, and replace/rename its directory entry during the read. Acceptance uses the actual opened descriptor plus path-entry revalidation. Evidence is rejected when it no longer describes the observation.

**AT-09 — Streaming and resource bounds.** Hash a large generated/sparse fixture with a small heap and bounded read buffers. Peak memory is not proportional to file size. File descriptors close after success, injected exceptions, pause, cancel, and shutdown. Sparse-file content hashing still reads the logical bytes.

## B. Inventory and durable control

**AT-10 — Complete recursive inventory.** A multi-root fixture includes nested/empty directories, hidden files, mixed extensions, zero-byte files, and equal sizes across roots. Store the expected metadata and scan ID for each accessible entry. Inventory performs no file-body hashing/sniffing/indexing.

**AT-11 — Metadata precision.** Preserve nanosecond mtime/ctime differences that collapse to the same PostgreSQL display timestamp. A one-nanosecond supported fingerprint difference is detected. Missing birth time is null; ctime is not shown as creation time.

**AT-12 — Enumeration errors.** Inject inaccessible directories, unreadable files, disappearing entries, and unavailable roots. Unrelated work continues. Coverage is partial/unavailable with structured errors; nothing is declared globally absent or clean.

**AT-13 — Replay after discovery crash.** Crash after committing a batch but before marking the directory complete, and after the final batch before completion marking. Restart/replay creates no duplicate observation or child work. Conflicting metadata is marked unstable instead of silently replacing the first observation.

**AT-14 — Pause/resume discovery.** Pause during a large directory. Durable status reaches PAUSED only after the safe point. Resume replays unfinished enumeration idempotently, retaining committed entries and counters. No array-position resume assumption is used.

**AT-15 — Cancel and state races.** Cancel queued, paused, running, interrupted, and pause-requested jobs. Repeated requests are idempotent. Cancel wins a concurrent pause request. Terminal jobs cannot resume. Completion/cancel transactions select one valid outcome. Committed partial records remain browsable.

**AT-16 — UI disconnect and process loss.** Close the browser while scanning; work continues. Kill/restart the backend; work becomes INTERRUPTED and is resumable only after validation and explicit action by default. A persisted cancel intent completes as cancelled.

**AT-17 — Source/config mismatch on resume.** Remove a mount, replace its dataset identity, change a binding/config revision, or introduce a writable included mount. Resume is blocked with a specific reason. Historical observations/notes remain intact.

**AT-18 — Lease fencing and database failure.** Expire a work lease, recover it, then allow the old worker to finish. Its token cannot commit. Start a second scheduler and verify single ownership. Interrupt PostgreSQL connectivity during a result commit: no fabricated completion or lost acknowledged work; retry is idempotent.

## C. Hashing and duplicate evidence

**AT-19 — Default unique-size policy.** Inventory one unique-size file and repeated-size groups. Only repeated-size files receive automatic source-content hash requests. A sole empty file also remains unhashed.

**AT-20 — Full-file SHA-256.** Use known benign fixtures, including the bytes `hello` without newline. Compare accepted digests to an independent trusted implementation. Check 32-byte storage, 64-character lowercase API hex, algorithm, byte count, start/end times, and no timestamp refresh on reuse.

**AT-21 — Same size is not content equality.** Two 5-byte files with different content are both hashed but never grouped together. A duplicate renamed to a different extension still belongs to its content group.

**AT-22 — Empty and cross-root duplicates.** Two empty files hash successfully and form a zero-byte group. Same-size identical files on different roots selected in one scan group normally.

**AT-23 — Manual unique-size hashing.** Explicitly request a checksum for a unique-size file. It is calculated through a durable job with MANUAL provenance. Repeating the request without force reuses the same accepted attempt/time when valid.

**AT-24 — Pause/cancel a large hash.** Interrupt a hash before publication. No partial digest, in-progress attempt, or partial byte counter is persisted. Resume starts the unfinished file at byte zero and preserves already completed file-level hashes.

**AT-25 — Source changes.** Modify, truncate, enlarge, replace, or remove a file before/during hashing. Pre/post/identity validation rejects mismatching evidence. Original inventory metadata remains historical; current results become stale or incomplete.

**AT-26 — Rehash conflict.** Inject a forced rehash result that disagrees with the previous accepted digest for a nominally unchanged observation. Invalidate active evidence and dependent groups/plans; do not silently overwrite it or publish both as current.

**AT-27 — History isolation.** Scan the same tree twice. Notes may persist, but the second scan's candidate set/groups contain only its observations. No prior-scan hash is silently accepted for the new scan, and a single path observed twice is not a two-copy group.

**AT-28 — Atomic group revisions.** Pause/crash halfway through grouping and complete a new manual hash concurrently with a later analysis build. Published revisions contain a consistent captured input set; unfinished generations are not current. Old invalidated evidence is visibly stale immediately.

## D. Explorer, annotations, and metadata search

**AT-29 — Database-backed browsing.** Disconnect the source after a completed scan and browse history. Stored directories/files remain visible with source-unavailable status. Explorer navigation performs no implicit live recursive scan.

**AT-30 — Combined filters.** Exercise exact/literal filename/path, extension, subtree, inclusive size bounds, UTC half-open date ranges, full checksum/prefix, hash state, duplicate state, tags ANY/ALL, memo, review state, and error/stale filters. Literal `%` and `_` are not SQL wildcards.

**AT-31 — Pagination and sorting.** Traverse a large equal-sort-key dataset using keyset cursors. No duplicate/skipped records appear in a stable revision. Changed filters or input revisions produce the documented cursor behavior rather than silent drift.

**AT-32 — Notes and replacement content.** Add a memo/manual tags, rescan the same location, then replace its content and rescan again. Notes persist as location notes; changed-content review warning appears. A renamed new location does not silently inherit old manual notes.

**AT-33 — Optimistic concurrency and bulk selection.** Edit the same memo from two tabs; the stale version receives a conflict. Freeze “all matching” selections before a growing scan adds rows; bulk changes affect only the frozen target set and generate audit records.

## E. Signature catalog

**AT-34 — Confirmed content match.** A valid size/SHA-256 signature matches a hashed observation exactly and shows its name, memo, tags, signature revision, and match time.

**AT-35 — Filename behavior.** Renamed matching content is confirmed in ADVISORY mode. REQUIRED_EXACT prevents a differently named match and warns about that restriction. Filename resemblance alone is not confirmed evidence.

**AT-36 — Unique-size coverage gap.** A file whose size matches a known signature but is unique in the scan remains unhashed/HASH_REQUIRED by default. It is not reported as clean or confirmed. Explicit “Check known signatures” hashes it and resolves the match.

**AT-37 — Explicit scan coverage option.** With `includeSignatureCandidates=true`, candidate selection equals duplicate sizes union signature sizes, with no duplicated hash task for an observation in both sets. Other unique-size files remain unhashed.

**AT-38 — Catalog-only rematching.** Add/edit/disable signatures while an existing-hash rematch runs. Each result references a captured catalog revision; a later rematch updates current findings. No extra file-body reads occur without consent. Historical findings remain.

**AT-39 — Annotation provenance.** Derived labels coexist with manual notes/tags. Disabling a signature removes only active derived presentation. It never deletes an independently entered manual tag or memo.

**AT-40 — Import validation/atomicity.** Exercise JSON/CSV, invalid hashes/algorithms/sizes/base64, unknown schema versions, duplicate IDs, duplicate fingerprints with distinct labels, expected-revision conflicts, oversized uploads, dry run, and apply. A failed import makes no partial active-catalog changes.

**AT-41 — Create from observation.** A hashed file supplies its real size/digest. An unhashed file prompts an explicit hash job before signature creation. No digest is fabricated from name/size. Non-UTF-8 advisory/required filenames retain bytes.

## F. UI and API security

**AT-42 — Exact large values.** Return file/aggregate byte values above JavaScript's safe integer range in a database fixture. JSON uses decimal strings and UI sorting/calculation/display do not round. Aggregate sums do not overflow signed 64-bit arithmetic.

**AT-43 — Authorization and CSRF.** Unauthenticated source/scan/annotation/content/export endpoints reject access. Valid sessions work. State-changing requests without a valid CSRF token fail. Cookie behavior matches HTTP-local and HTTPS-deployed modes. No permissive credentialed wildcard CORS.

**AT-44 — Endpoint absence.** Inspect OpenAPI and runtime routes for source deletion, arbitrary paths, raw source downloads, command execution, and export execution. None exists. Login/job-creation limits work and placeholder credentials are rejected outside tests.

**AT-45 — Rendering injection.** Render filenames, notes, tags, signatures, and content containing HTML/script/event attributes and control characters. They display only as escaped data. Generated download names cannot inject response headers.

## G. Text-content indexing

**AT-46 — Disabled means disabled.** With text indexing off, no extractor reads source bodies and the UI labels content as not indexed. Metadata/hash search continues to work independently.

**AT-47 — Searchable supported text.** Explicitly index UTF-8 and BOM-marked UTF-16 fixtures from allowed extensions. Token searches and literal substring/case-sensitive searches return the defined, distinguishable results with bounded escaped excerpts.

**AT-48 — No implicit checksum.** Index a unique-size file without a hash authorization. Text becomes searchable but no SHA-256 attempt/result is created for that source file.

**AT-49 — Unsupported/limited coverage.** Include PDF, image, archive, binary disguised as text, unsupported encoding, oversized source, and text exceeding extraction limit. Correct statuses distinguish unsupported, too large, binary, encoding failure, full, and truncated indexing. No silent “searched everything” claim.

**AT-50 — Inert content.** HTML/JavaScript/XML text containing external URLs and entity references is indexed only as plain text. No JavaScript execution, entity expansion, network fetch, or active-document rendering occurs.

**AT-51 — Index interruption/staleness.** Pause/cancel/restart indexing and change a file during extraction. Partial text is not published as a complete index. Changed inputs become stale and are excluded from current-evidence search by default. Coverage counts agree with stored statuses.

## H. Plans, verification, and space calculations

**AT-52 — Immutable plan input selection.** Capture a scope/filter and analysis revision, then change results or annotations. The plan retains its captured input and becomes stale/needs review when relevant, instead of silently selecting a different set.

**AT-53 — Independent-copy formula.** Three separate 100-byte identical files with reliable identities produce 300 logical object bytes and a theoretical 200-byte duplicate-copy reduction when retaining one. Partial or missing allocation data never becomes exact physical savings.

**AT-54 — Hard-link-only group.** Two paths to one 100-byte inode show two paths, one object, and zero duplicate-copy bytes. Selecting one link for removal does not claim 100 bytes reclaimed.

**AT-55 — Hard links plus separate copy.** Two links to object A and an independent identical object B represent two objects, not three. Removing the eligible B while retaining A suggests 100 logical bytes. Removing A is eligible only when all known actual links are selected and link coverage is complete.

**AT-56 — Unseen links, aliases, and uncertainty.** Give an inode a link outside scan scope, repeat a bind-mount view, or remove reliable identity/allocation capabilities. Automatic plan metrics exclude ineligible/unknown objects or show explicit partial values. Snapshots/reflinks/sparse files never receive a guaranteed physical-free-space figure.

**AT-57 — Keeper enforcement.** Try to mark all independent objects in a duplicate group as removal candidates, choose a candidate as its own keeper, or remove a protected KEEP path. The server rejects invalid plans, even when API calls bypass the UI. Deterministic tie-breaking is stable.

**AT-58 — Byte verification.** Verify real identical files successfully. Inject same-hash metadata for different byte streams and verify a conflict. A paused or failed pair does not mark the whole group BYTE_VERIFIED. Changed reference metadata invalidates the comparison.

**AT-59 — Plan validation.** Change/remove a candidate or its keeper between scan, plan, and metadata/rehash validation. The relevant group becomes blocked/stale. A successful metadata-only validation is labeled weaker than rehash and is not a future deletion guarantee.

**AT-60 — Plan types.** A FILE_REVIEW list can include a unique signature match without inventing a duplicate keeper. Its UI/export is clearly different from DEDUPLICATION_REVIEW and no type executes removal.

## I. Exports

**AT-61 — Frozen rows and provenance.** Change notes/results while an export builds. Export rows match the captured IDs, evidence, plan, metadata, and signature revisions consistently. Row count and artifact digest agree with the complete file.

**AT-62 — Exact path round-trip.** Export AT-06 filenames to JSONL/base64 and NUL data with manifest. Recover exact original bytes. The CSV's display-cell text is not substituted as the lossless filename.

**AT-63 — Spreadsheet injection.** Export cells beginning with `=`, `+`, `-`, `@`, tab/CR/LF, and prefixes following control/whitespace, as well as quotes/commas/newlines. CSV is structurally valid and textual formula payloads are neutralized. JSONL exact fields remain accurate.

**AT-64 — Host mapping.** Container path and host prefix differ. Portable source-relative identity is correct. A host path is emitted only with acknowledged configured mapping; unavailable mapping is explicit. No client-controlled path escapes the mapping or artifact store.

**AT-65 — Export interruption/publication.** Pause/cancel/crash during export assembly. No incomplete artifact is downloadable as complete. Resumption/retry has no duplicate rows, and application-owned temporary cleanup never touches sources.

**AT-66 — Descriptive output only.** Inventory/findings/plan exports include scope, coverage, status, expected metadata/hash/time, keeper where applicable, and review-only/revalidation warnings. No executable command, deletion script, execution endpoint, or automatic action is produced.

**AT-67 — Partial and stale reports.** Export partial inventory and stale evidence deliberately. The manifest labels them accordingly; a normal deduplication plan cannot silently treat them as current complete inventory. Missing hashes remain null with explicit reasons.

## J. End-to-end and operations

**AT-68 — Full browser workflow.** Through Playwright: login, scan mounted fixtures, view progress, pause/resume, inspect directories, calculate a manual hash, annotate, filter, import/apply a signature, check a unique-size match, explicitly index/search text, review duplicate metrics/keepers, validate, and export. All data is real backend/PostgreSQL data.

**AT-69 — Resource/scale profile.** Run at least a million-row database fixture with representative paths, tags, sizes, duplicate distributions, and text coverage. Verify bounded memory, keyset pages, indexed query plans, control responsiveness, and recorded measurements. The optional 10-million profile has reproducible setup; unrun benchmarks are not claimed.

**AT-70 — Worst-case candidates.** Use many files of the same size so every file is eligible. Counts/read estimates remain accurate, concurrency/throttles are enforced, and no optimization silently skips files with different extensions/names.

**AT-71 — Limits and failure isolation.** Exercise queue limits, export storage quota, full artifact volume, malformed requests, query timeout, and transient DB/source failure. Responses are explicit, incomplete outputs remain unpublished, and existing history/ready exports remain valid.

**AT-72 — Operational privacy.** Logs/metrics omit credentials, raw file bodies, extracted text, memos, and high-cardinality filename labels. Application runtime performs no external HTTP requests for content/telemetry/resources. Audit events identify actual actor/actions without leaking secrets.

**AT-73 — Backup/restore and redeployment.** Back up application PostgreSQL/artifacts in the documented consistent way, restore to an isolated test deployment, and confirm scans, annotations, signatures, plan evidence, and artifact access. Source remapping requires identity validation. Document any schema rollback restrictions.

**AT-74 — Fresh operator handoff.** Follow setup/adding-root/restart/resume instructions from a fresh environment, preserving existing repository license and private secrets. Verify the source remains read-only after an upgrade. `VERIFICATION.md` lists exact tested commit/tool versions and explicitly identifies anything untested.

## Requirement-to-test traceability

| User requirement | Primary tests |
|---|---|
| Groovy, Spring Boot web service, Angular, Docker, PostgreSQL | AT-01, AT-43, AT-68, AT-74 |
| Recursive files and useful metadata/scan IDs | AT-06, AT-10–AT-13 |
| Hash only repeated sizes by default; manual override | AT-19–AT-24, AT-36–AT-37, AT-48 |
| Duplicate identification | AT-21–AT-22, AT-27–AT-28, AT-53–AT-58 |
| Progress, safe stop/resume/cancel | AT-13–AT-18, AT-24, AT-51, AT-65 |
| Directory explorer and file notes/tags | AT-29–AT-33, AT-39 |
| Size/date/checksum/content search | AT-30–AT-31, AT-42, AT-46–AT-51 |
| Space reduction calculations | AT-53–AT-57 |
| Export lists for external review/removal tools | AT-60–AT-67 |
| Named/memo/tagged signature catalog and automatic findings | AT-34–AT-41 |
| Roots configured through Docker mounts | AT-03–AT-05, AT-17, AT-64, AT-74 |
| Never delete; identification only | AT-02–AT-05, AT-44, AT-60, AT-66 |
| English-only, no internationalization | Review source dependencies/routes/labels during AT-01/AT-74; retain AT-06 Unicode/path support |
