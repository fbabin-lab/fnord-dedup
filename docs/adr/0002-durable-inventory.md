# ADR 0002 — Durable, inventory-only execution

Status: Implemented for M1 draft; native PostgreSQL concurrency and Docker gates remain open.

M1 completes the inventory phase only. The API/UI report `inventoryOnly=true` and `analysisAvailable=false`. Later milestones add the other scan phases; current completion never asserts that checksums, duplicates, signatures or text indexing ran.

## Persistence and identity

Migration V2 introduces `source_root`, `file_location`, `scan`, `scan_source`, `scan_entry`, `observation_validation`, `job`, `work_item`, `job_event`, `job_error` and `idempotency_record`. A scan references its sole inventory job through `job.scan_id` (unique). Root configuration snapshots live in `scan_source`; configuration revisions are retained by V1. `source_root.snapshot` is the most recently used configuration, while scan snapshots remain historical.

Location uniqueness uses source ID, instance ID, parent ID and raw basename, with `NULLS NOT DISTINCT` for roots. Composite foreign keys prevent parent/location/observation references from crossing source instances. Full relative paths are bytea and are not B-tree uniqueness keys. Original metadata is inserted once per scan/location; replay conflicts append the first instability record and an idempotent error. Only directory coverage is updated on an observation row. There are no content reads in the inventory worker.

Counts increment in the same transaction as a new observation/work item. Directory work tracks unresolved child directories and subtree issues. Finishing a leaf propagates resolution one ancestor at a time. This avoids loading the tree or running a whole-catalog aggregate to calculate coverage. The root stays PARTIAL while any subtree is unfinished. Errors and excluded mounts propagate partial coverage; a root whose metadata cannot be obtained becomes UNAVAILABLE.

## Ownership and safe stopping

`scheduler_lock` is a singleton database lease with an owner UUID and monotonically increasing epoch. Every claim and result transaction locks that row, then the job row, then the work row. A second backend cannot acquire the live lease. Work items additionally carry owner, token and expiry. The worker heartbeat renews every ten seconds, with sixty-second leases; it never resurrects an already expired work lease. A result batch checks its fences before writes and again before returning from the transaction. No filesystem operation runs inside that transaction.

Claims use `FOR UPDATE SKIP LOCKED`. There is a unique partial index permitting only one RUNNING/PAUSE_REQUESTED/CANCEL_REQUESTED top-level job. A single server thread performs filesystem work; a separate scheduled thread renews leases. Java executors are dispatch mechanisms, not the source of work truth. The database owns all work and controls.

Inventory commits at most 500 entries per batch, or after approximately one second of enumeration. It persists child work atomically with observations. A completed directory means its enumeration reached EOF and all emitted rows/work were committed. An unfinished directory replays from the beginning. Directory offsets are never persisted.

Pause/cancel throws a private control exception through try-with-resources so cursor/root handles close before acknowledging PAUSED/CANCELLED. Cancellation wins a pause race. Job-row locking serializes controls and completion. Cancellation leaves committed observations and unfinished task records intact; the terminal job state fences any old writer without an unbounded task-table update.

After lease takeover or orderly worker release, active jobs become INTERRUPTED and leased tasks become READY with increased tokens. Pending cancellation becomes CANCELLED. Explicit resume validates all selected sources/configuration/root identities, then compares job version under lock. A database failure never fabricates completion; ambiguous batches replay idempotently after recovery. Shutdown stops dispatch, waits up to twenty seconds for safe closure, and allows a blocked syscall's lease to expire instead of force-stopping its thread.

## Bounds and queries

Whole-root scans select at most 100 configured sources. Admission serializes only short DB creation transactions, permits at most ten unfinished jobs and five creations per actor per minute, and serves idempotent replay before checking capacity. Keys currently have no automatic purge, preserving more than the minimum 24 hours.

Pages are bounded and use monotonic sequence cutoffs/keyset cursors, never OFFSET. GET handlers read PostgreSQL and cached source status only. The browser polls progress every ten seconds and stops polling on destruction; this has no effect on the worker. Counters and byte values use decimal strings, with NUMERIC(38,0) aggregate bytes and unsigned inode values stored as NUMERIC(20,0).

No additional runtime dependency or source capability was introduced. Test-only fixture servers and writable-fixture mount overrides live in the test source set and are absent from the production JAR.

Production Angular builds disable critical-CSS inlining. Its generated inline load handler is incompatible with the existing `script-src self` policy; a normal stylesheet link loads all styles without relaxing CSP. Browser checks assert the actual computed layout under the production policy.
