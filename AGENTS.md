# AI implementation instructions — Fnord Dedup

Read `docs/SPECIFICATION.md`, `docs/IMPLEMENTATION_PLAN.md`, and `docs/ACCEPTANCE_TESTS.md` before changing code. The specification is authoritative. This file does not authorize product-scope changes.

## Non-negotiable constraints

The application only inventories, hashes, indexes explicitly authorized text, matches signatures, annotates database records, calculates review plans, and exports descriptive lists. It never deletes, renames, moves, quarantines, modifies, relinks, chmods, or executes scanned files. Do not add an execution path for exported plans. No generated deletion commands or shell scripts. Source bind mounts are read-only; fail closed on writable included mounts without trying to write a probe.

Backend application code is Groovy with Spring Boot. Frontend is Angular/TypeScript. Persistence is PostgreSQL with Flyway. Deployment is Docker Compose. Use English only, without internationalization. Preserve the existing repository license and unrelated files.

Use SHA-256, not SHA-1. The default scan hashes only files whose sizes occur at least twice in that scan. Additional unique-size hashing requires an explicit manual request or an explicitly selected signature-candidate option. Do not add an implicit hash to content indexing. Signature matching is exact content-fingerprint matching, not filename classification or legal judgment.

Every accepted hash belongs to one scan observation and full successful read. Never accept partial digests or silently reuse a prior scan's hash. Check descriptor metadata before/after reading and invalidate evidence on source changes. Same file in two scans is not two copies. Hard-link paths are not separately stored copies. Report physical savings as unknown, not guaranteed.

Persist jobs, work claims, control requests, checkpoints, and fencing tokens in PostgreSQL. Pausing/cancelling is cooperative. Restart incomplete file reads at byte zero; replay unfinished directories idempotently. Never rely on an in-memory queue as the sole source of work. Never claim instant cancellation of blocked filesystem I/O.

## Implementation practice

Work milestone by milestone. Start with the Linux read-only access and version-compatibility risks, not a decorative UI. Keep native filesystem operations confined to the adapter. Implement raw filename-byte support and exact integer arithmetic; do not use lossy display paths as filesystem identities or JavaScript floating-point numbers for unrestricted byte totals.

Use statically compiled Groovy for production logic, parameterized SQL, bounded streams/batches, database indexes, and keyset pagination. No shell execution over source paths, dynamic Groovy evaluation, archive unpacking, remote resource fetching, or arbitrary-file serving endpoint. Tests use benign generated fixtures only.

Maintain the OpenAPI contract, migrations, Angular types, documentation, and tests together. An explicit opt-in feature is still required v1 functionality; do not silently drop text indexing or signature checking because the default is off. Office/PDF/OCR/perceptual matching is explicitly outside v1.

Review imports and exports as untrusted data. Freeze selections/revisions. CSV display strings are not lossless paths; use documented JSONL/base64/NUL formats for exact names. Derived signature annotations must remain separate from manual notes/tags.

## Verification and reporting

Run the milestone's unit, PostgreSQL integration, Linux fixture, and browser/Docker tests that are available. Record exact commands, outcomes, versions, and untested capabilities in `docs/VERIFICATION.md`. Never invent successful tests or performance figures. A failure in filesystem safety, interruption recovery, or evidence correctness is a blocker, not a cosmetic caveat.

Do not test source mutation against operator data. Destructive negative tests are confined to generated disposable fixtures and must demonstrate that the deployed scan mount rejects writes. Preflight on configured real sources is inspection-only.

Do not force-push, change repository visibility/license, merge a PR, deploy to a real server, or modify actual host mounts unless the user explicitly requests the corresponding action. When asked to stop at a milestone, provide achieved scope, test evidence, limitations, and the next milestone; do not promise background implementation.
