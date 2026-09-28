# Fnord Dedup — implementation milestones

**Baseline:** Specification v1.0, September 28, 2026.  
**Target:** `fbabin-lab/fnord-dedup`.  
**Purpose:** Build a complete system in reviewable, testable increments without adding destructive capabilities.

Milestones are dependency-ordered delivery boundaries, not elapsed-time estimates. Each milestone requires source, tests, and documentation. Read `SPECIFICATION.md` for normative behavior and `ACCEPTANCE_TESTS.md` for the test matrix. Use one feature branch/PR per milestone or another explicitly agreed workflow; do not merge without authorization.

## M0 — Architecture, compatibility, and source-safety foundation

Create the Groovy/Spring Boot backend and Angular skeleton, pin compatible dependencies, create Dockerfiles/base Compose, PostgreSQL/Flyway connectivity, single-operator authentication, and a minimal frontend shell. Preserve the existing license. Configure source registry and strict read-only mount checks.

Implement a narrow Linux read-only filesystem adapter proof of operation before committing to high-level scanning logic. Demonstrate safe directory enumeration, raw filename bytes, descriptor-based metadata, no-follow/root-confined opening, regular-file validation, bounded reads, and handle closure. Pin/test the native binding and supported architecture. Do not call a pathname-only implementation race-safe.

Deliver baseline OpenAPI, first migrations, `.env.example`, source-config examples, preflight, initial `OPERATIONS.md`, and an ADR documenting framework versions, native binding, and source safety.

**Exit gate:** Compose starts and healthy services communicate; unauthenticated data access is rejected; writable roots and symlink escapes are rejected; generated normal/unusual-name fixtures can be read safely; no source write path exists. Tests AT-01–AT-09, AT-43, and AT-44 pass. No mock filesystem result is presented as a real scan.

## M1 — Durable inventory and scan control

Implement scan creation, immutable source/config snapshots, persistent directory work, metadata observations, per-root coverage, durable job statuses, leases/fencing, idempotency keys, and safe pause/resume/cancel. Build a basic scan list/detail UI with progress polling and errors.

Discovery uses bounded transactions and resumable directory replay. Browser disconnection has no effect on work. On process restart, interrupted jobs remain visible and require explicit resume. A missing root never marks all historical files deleted.

**Exit gate:** A real mounted fixture tree is inventoried recursively, including hidden files, empty directories, symlinks as metadata, errors, and large directories. Pause/cancel/restart at injected boundaries causes no duplicate or lost committed records. The explorer can show committed partial results. Tests AT-10–AT-18 and applicable AT-06–AT-09 pass.

## M2 — Size-candidate hashing and duplicate analysis

Add size-set materialization, SHA-256 tasks, per-file timestamps/reasons, accepted-evidence validation, manual hash/force actions, bounded hash streams, and duplicate-group revisions. Add file detail and duplicate-group views.

Unique-size files are not hashed by default. Distinguish no hash, failed hash, stale hash, and accepted complete hash. Rehash conflicts invalidate affected groups. Hard-link/alias classification is visible and historical scans remain separate.

**Exit gate:** Known fixture content produces correct hashes/groups; same-size different-content files remain separate; renames do not affect content equality; a changing file never publishes a successful digest for the wrong observation; interruption never persists a partial digest. Tests AT-19–AT-28 and AT-18 pass.

## M3 — Explorer, metadata search, memos, and tags

Complete lazy database directory navigation, metadata filters and sorting, keyset paging, exact byte handling in Angular, memo/tag/review-state forms, optimistic concurrency, audit events, and frozen bulk selection.

Keep location notes across rescans but warn on changed file identity/content. Do not copy annotations automatically to renamed locations. Escape all untrusted display text and preserve unusual filenames in identity fields.

**Exit gate:** Real scan results are navigable/searchable without rereading the source filesystem. Notes survive a new scan of the same location. Two-tab conflicts are explicit. Large byte values remain exact. Tests AT-29–AT-33, AT-42, AT-45, and AT-52 pass.

## M4 — Signature catalog and explicit broader coverage

Implement versioned signature records, tags/memos, create-from-observation, JSON/CSV dry-run imports, explicit atomic apply, exports, exact content matching, derived annotation provenance, and coverage states.

Add the default-off scan signature-candidate option and on-demand “Check known signatures.” Reconcile catalog edits with already available hashes using database-only rematching; do not silently read unique-size files. Multiple labels for one fingerprint must coexist. Preserve catalog/finding revision history.

**Exit gate:** Renamed known content matches in advisory-filename mode; unique-size signature files remain `HASH_REQUIRED` by default and become confirmed after explicit checking; original manual notes are never overwritten. Imports are atomic and version conflict behavior is tested. Tests AT-34–AT-41 pass.

## M5 — Bounded text indexing and content search

Implement the default-off text indexing job and supported plain-text/code/configuration formats, encoding/size limits, pre/post input validation, PostgreSQL text indexing, token and literal-substring queries, coverage states, and escaped snippets.

No broad document parser, OCR, archive extraction, script evaluation, or network fetch. Indexing a unique-size file must not create a content hash without separate authorization. Show unsupported/truncated/stale status in search results and details.

**Exit gate:** Supported text is genuinely searchable, disabled indexing does not read bodies, unsupported formats are honest, limits are enforced, cancellation/restart is safe, and HTML remains text. Tests AT-46–AT-51 pass.

## M6 — Verification, exact space analysis, and review plans

Add optional byte-by-byte group verification, physical-object/link accounting, logical/allocated/unknown-physical metrics, deterministic keeper policy with protected files, manual overrides, duplicate and file-review plan types, stale-plan detection, and explicit metadata/rehash validation jobs.

Group calculations do not mix scans or double-count hard links. Unseen links and unreliable identity reduce planning eligibility. “No more than all but one independent object” is enforced on the server, not just in the UI. Separate unwanted-file review lists from duplicate-keeper plans.

**Exit gate:** Formula fixtures calculate expected logical duplicate-copy bytes exactly. Hard-link-only groups report zero duplicated object bytes. It is impossible to save a deduplication plan with no valid keeper. Injected hash/byte disagreements and missing keepers block eligibility. Tests AT-53–AT-60 pass.

## M7 — Safe, reproducible exports

Implement inventory, filtered, duplicate, findings, signature, and review-plan exports as durable jobs. Freeze selected IDs/evidence/annotations; write only to application-owned storage; publish completed artifacts atomically. Implement canonical JSONL, spreadsheet-safe CSV, and optional explicit NUL path data plus companion manifest.

Capture source mappings, raw filename bytes, expected metadata/hashes, keepers, coverage, revision, and review-only warnings. Never generate executable removal commands or run an export. Verify host/container path distinction and exact byte round trips.

**Exit gate:** Downloaded artifacts agree with frozen database selection, CSV payloads cannot activate formulas, adversarial paths round-trip in machine formats, and cancellation never exposes partial output as complete. Tests AT-61–AT-67 pass.

## M8 — End-to-end hardening, scale validation, and handoff

Run the complete acceptance suite under Docker, verify authorization/CSRF and no-write enforcement, test process/database interruption, exercise mount/config changes, and run the 1-million-row database scale profile. Provide a reproducible optional 10-million-row benchmark profile without asserting unmeasured performance.

Finalize health, logs, metrics, quotas, documentation, backup/restore exercise, deployment examples, and AI guidance. Test the fresh-checkout setup instructions. Ensure no secrets, private source paths, extracted real data, or hazardous sample signatures are committed.

**Exit gate:** All mandatory acceptance tests pass or a precisely scoped environment limitation is explicitly documented and blocks the corresponding unsupported deployment claim. `VERIFICATION.md` records exact versions, commit, commands, results, and limitations. No placeholders remain for required v1 features. Tests AT-68–AT-74 and the full regression suite pass.

## Cross-milestone rules

Every milestone maintains INV-01 through INV-10. Safety or evidence defects block later feature work until fixed. Tests using mutation are limited to disposable fixtures with no operator data. Application exports are never executed by the application or its test suite against real sources.

The implementer may split a milestone into smaller commits, but cannot remove required functionality by declaring it “future work.” Conversely, explicitly excluded features remain excluded. A change to default hashing, evidence scope, source write capabilities, indexing consent, or export execution requires a new specification and user approval.

At each stopping point report the milestone, actual implemented behavior, exact tests and outcomes, repository branch/commit or artifact, limitations, and the next dependency-ordered step. Do not report merely planned tests as passed.
