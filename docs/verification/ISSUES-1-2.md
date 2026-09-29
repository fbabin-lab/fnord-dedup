# Review issues 1 and 2 — verification addendum

Date: September 29, 2026. Base: `a6e15cf8e98387a352d98e4e8eaa27651042cbc8` (`fix/signature-hash-retry`, PR #6).

This focused addendum supplements `docs/VERIFICATION.md` without replacing its historical M0-M4 results. Issue 3 remains included and unchanged. Nothing is merged or deployed.

## Changes

### 1. Annotation baseline validity

Add Flyway V6, replacing `observation_facts` in place. The annotation warning now checks the baseline observation's live `accepted_hash.active` separately from its historical hash attempt. A failed recheck such as READ_FAILED or UNREADABLE therefore warns on later observations even without an observation-validation record, scan-wide invalidation, metadata difference, or digest mismatch. The later observation need not have a checksum.

Metadata-only notes still do not require hashes. A successful same-content rehash does not become an annotation warning merely because its accepted-attempt ID changes. Existing notes, tags, versions, historical digests and V1-V5 migration checksums are retained. The search clock advances once so pre-migration query tokens cannot silently reuse the older warning/filter semantics. Existing selection validation consumes the corrected warning without new selection code.

### 2. Signature filename editing

Typing or programmatically editing the ordinary filename clears its previous base64 override. The existing server then derives exact UTF-8 basename bytes. Explicit raw-byte edits remain authoritative. Loading/reloading a saved record suppresses change notifications, preserving non-UTF-8 and escaped-display basenames during memo/tag/other-field edits. The filename subscription is disposed with the component. The editor explains these rules.

No source-access code, read-consent defaults, API schema or generated types changed. The issue-3 hash retry method and all later catalog methods are byte-for-byte unchanged from the base commit.

## Executed checks

The sandbox lacks the repository's Angular/Gradle dependency installations and a PostgreSQL server; direct GitHub/package network access fails DNS resolution. GitHub changes were made through the connected repository tools. No unavailable suite is reported as passed.

| Check | Original implementation | Fixed implementation |
|---|---|---|
| Annotation predicate and actual joins, isolated SQLite fixtures | 18 cases: 15 passed, 3 failed | 18 passed |
| Actual transpiled signature component, isolated form/API stand-ins | 12 cases: 6 passed, 6 failed | 12 passed |
| Existing issue-3 retry regression harness, adapted only for form-change events | Not rerun against the pre-issue-3 code | 24 passed |
| TypeScript syntax transpilation: component and new filename spec | Not repeated | Zero syntax diagnostics |

Total: **54 isolated checks passed**, zero failures/skips in the fixed runs. The expected before-fix failures include both reviewed defects. These checks are not the Angular runner or the PostgreSQL suite. SQLite executes the warning expression and joins extracted from the view, not Flyway or the full PostgreSQL-only view. The component harness executes the real TypeScript component with lightweight Angular/form/HTTP stand-ins, not a browser or real server.

Executed environment: Node 22.16.0, TypeScript 5.8.3, Python 3.13, SQLite 3.46.1. These are harness versions, not changes to the pinned project toolchain.

Commands from the supplied verification bundle root:

```bash
SQL_SOURCE=base/observation_facts.sql python verification/verify-baseline-predicate.py
python verification/verify-baseline-predicate.py
NODE_PATH="$(npm root -g)" SIGNATURES_SOURCE="$PWD/base/signatures.ts" node --test verification/verify-filename.cjs
NODE_PATH="$(npm root -g)" node --test verification/verify-filename.cjs
NODE_PATH="$(npm root -g)" node --test verification/verify-issue3.cjs
NODE_PATH="$(npm root -g)" node verification/check-syntax.cjs
```

The first and third commands are expected to fail against the original code. Before/after logs and the standalone harnesses are included in the response's verification bundle. The copied base component matches repository blob `168b289588bc560604b2dad387ff4828a93c5880` exactly. Whitespace checks pass.

## Added native regression tests — not executed here

- `AnnotationBaselineIntegrationTest`: six PostgreSQL/native-fixture cases. Four parameterized READ_FAILED/UNREADABLE cases cover hashed and unhashed later observations, preserved notes/tags/attempts, stale cursors and selections, unrelated selections, explicit re-review, and database-only browsing. Two controls cover healthy accepted-attempt replacement and metadata-only notes.
- `SignatureFilenameTest`: four Groovy/JUnit cases for exact UTF-8 derivation after clearing the override, authoritative non-UTF-8 bytes, empty required-exact rejection, and removal of optional advisory bytes.
- `signatures-filename.spec.ts`: thirteen Angular/Vitest cases, including typing into the rendered input, Unicode/decomposed Unicode, non-UTF-8/control-byte preservation, intentional overrides, conflict/reload behavior, fresh editors and subscription disposal.

Run the normal locked-toolchain backend unit/integration suites, Angular tests/production build/API generation check, and browser regressions before merging. Native PostgreSQL migration execution (fresh and existing data), concurrent sessions/WAL durability, process-kill recovery and Docker/read-only-mount gates remain unverified here. Prior milestone pass counts have not been re-certified.
