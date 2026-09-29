# Issue 3 verification — signature observation hash retries

Date: September 29, 2026. Base: `a97e3636901fdc46a64e93be8e4b47ecdbc17285` (`feature/m4-signature-catalog`).

This is a focused addendum to `docs/VERIFICATION.md`; its historical M0–M4 results and deployment blockers remain unchanged. Review findings 1 and 2 are not addressed.

## Change

The signature editor binds a hash submission intent to the scan and observation, retains its idempotency key after an uncertain POST response, and records the acknowledged job ID before refreshing evidence. Another explicit click checks that job. Nonterminal states, including PAUSED, INTERRUPTED and CANCEL_REQUESTED, do not cause another submission. A known terminal job permits a new key only after a successful database-backed evidence refresh finds neither an accepted hash nor pending work. Failed status/evidence reads do not authorize a new attempt. In-flight feedback is guarded against editor changes and destruction. The notice acknowledges the returned job ID without asserting that a replayed response represents newly queued work.

No backend, API contract, generated types, migrations, source access or read-consent defaults changed. There is no automatic retry job or source read caused by refresh.

Production file Git blob: `168b289588bc560604b2dad387ff4828a93c5880`.
Regression spec Git blob: `5e662b3f0f2d68fa701cbdc30d00aa27aee34fae`.

## Executed checks

Environment: Linux/amd64, Node 22.16.0, TypeScript 5.8.3 available globally. These are the isolated harness tools, not the repository's pinned Angular toolchain.

A temporary Node test harness transpiled and executed the actual `Signatures` component. Angular dependency injection, signals and form controls were minimal stand-ins; API calls were controlled fakes. This validates retry control flow, not Angular rendering, actual HTTP idempotency, database behavior or source reads.

- Original source: 24 isolated checks; 2 passed, 22 failed. In particular, post-cancellation retry retained the old key.
- Fixed source: the same 24 checks passed; zero failures/skips. They cover all four terminal and six nonterminal states, ambiguous original/replacement responses, status and evidence failures, accepted/pending evidence, duplicate clicks, request identity and late responses.
- Extracted changed-method strict TypeScript check against minimal structural API contracts: zero diagnostics.
- TypeScript transpilation of the production component and new Angular regression spec: zero syntax diagnostics.
- Original component copy matched its retrieved Git blob exactly. The template and all methods following `hashObservation` remain byte-for-byte unchanged.

Exact local commands:

```bash
SIGNATURES_SOURCE=/mnt/data/signatures.original.ts node --test /mnt/data/verify-issue3.cjs
node --test /mnt/data/verify-issue3.cjs
node /mnt/data/typecheck-issue3.cjs
```

The temporary harness, original/fixed source, strict-check script and TAP results are included in the verification bundle supplied with the review response. The first command is expected to fail against the original source.

## Not executed / remaining checks

`frontend/src/app/signatures-hash-retry.spec.ts` adds 24 Angular/Vitest regression cases. The actual Angular/Vitest runner, Angular production build and Playwright browser suite were not run: those dependencies are not installed in this sandbox, and direct repository/package network access is unavailable. The isolated checks above are not substitutes for those suites. No backend/PostgreSQL/Docker suite was rerun for this frontend-only change.

Run the locked frontend suite and production build on the normal development host before merging. Keep the existing M4 native PostgreSQL concurrency/WAL, process-kill recovery and Docker/read-only-mount acceptance gates open. Nothing was merged or deployed.
