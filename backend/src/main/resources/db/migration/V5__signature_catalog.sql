CREATE TABLE signature_clock (id integer PRIMARY KEY CHECK (id=1), revision bigint NOT NULL DEFAULT 0);
INSERT INTO signature_clock(id) VALUES (1);
CREATE TABLE signature (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    revision bigint NOT NULL CHECK (revision>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE signature_revision (
    signature_id uuid NOT NULL REFERENCES signature(id),
    revision bigint NOT NULL CHECK (revision>0),
    catalog_revision bigint NOT NULL CHECK (catalog_revision>0),
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    memo text NOT NULL CHECK (length(memo)<=20000),
    size_bytes bigint NOT NULL CHECK (size_bytes>=0),
    algorithm text NOT NULL CHECK (algorithm='SHA-256'),
    digest bytea NOT NULL CHECK (octet_length(digest)=32),
    filename text,
    filename_bytes bytea CHECK (octet_length(filename_bytes) BETWEEN 1 AND 255),
    filename_match_mode text NOT NULL CHECK (filename_match_mode IN ('ADVISORY','REQUIRED_EXACT')),
    enabled boolean NOT NULL,
    origin text NOT NULL CHECK (origin IN ('MANUAL','FROM_OBSERVATION','IMPORT')),
    source_note text NOT NULL CHECK (length(source_note)<=2000),
    observation_id uuid REFERENCES scan_entry(id),
    attempt_id uuid REFERENCES hash_attempt(id),
    tags jsonb NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (signature_id,revision),
    UNIQUE (signature_id,catalog_revision),
    CHECK (filename_match_mode<>'REQUIRED_EXACT' OR filename_bytes IS NOT NULL)
);
ALTER TABLE signature ADD FOREIGN KEY (id,revision) REFERENCES signature_revision(signature_id,revision) DEFERRABLE INITIALLY DEFERRED;
CREATE TABLE signature_tag (
    signature_id uuid NOT NULL,
    revision bigint NOT NULL,
    tag_id uuid NOT NULL REFERENCES tag(id),
    PRIMARY KEY (signature_id,revision,tag_id),
    FOREIGN KEY (signature_id,revision) REFERENCES signature_revision(signature_id,revision)
);
CREATE INDEX signature_fingerprint ON signature_revision(size_bytes,digest,catalog_revision) WHERE enabled;
CREATE INDEX signature_snapshot ON signature_revision(signature_id,catalog_revision DESC);
CREATE INDEX signature_tag_lookup ON signature_tag(tag_id,signature_id,revision);
CREATE FUNCTION signatures_at(bigint) RETURNS SETOF signature_revision LANGUAGE sql STABLE AS $$
    SELECT r.* FROM signature_revision r WHERE r.catalog_revision<=$1
    AND NOT EXISTS (SELECT 1 FROM signature_revision n WHERE n.signature_id=r.signature_id
        AND n.catalog_revision<= $1 AND n.catalog_revision>r.catalog_revision)
$$;

ALTER TABLE scan ADD COLUMN signature_catalog_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE scan ADD COLUMN signature_scheduled_evidence bigint NOT NULL DEFAULT -1;
ALTER TABLE scan ADD COLUMN signature_scheduled_revision bigint NOT NULL DEFAULT -1;
ALTER TABLE scan ADD COLUMN current_signature_run_id uuid;
ALTER TABLE job DROP CONSTRAINT job_type_check;
ALTER TABLE job DROP CONSTRAINT job_phase_check;
ALTER TABLE job ADD CHECK (type IN ('INVENTORY','SCAN','HASH','SIGNATURE_CHECK','SIGNATURE_MATCH'));
ALTER TABLE job ADD CHECK (phase IN ('INVENTORY','CANDIDATE_SELECTION','HASHING','ANALYSIS','SIGNATURE_MATCH'));
ALTER TABLE job ADD COLUMN signature_catalog_revision bigint;
ALTER TABLE work_item DROP CONSTRAINT work_item_kind_check;
ALTER TABLE work_item ADD CHECK (kind IN ('DIRECTORY','SELECT_CANDIDATES','HASH','GROUP','SIGNATURE_SELECT','SIGNATURE_MATCH'));
CREATE TABLE signature_run (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    scan_id uuid NOT NULL REFERENCES scan(id),
    job_id uuid NOT NULL,
    catalog_revision bigint NOT NULL,
    evidence_revision bigint NOT NULL,
    entry_cutoff bigint NOT NULL,
    state text NOT NULL CHECK (state IN ('BUILDING','PUBLISHED','ABANDONED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    FOREIGN KEY (job_id,scan_id) REFERENCES job(id,scan_id),
    UNIQUE (id,scan_id)
);
ALTER TABLE scan ADD FOREIGN KEY (current_signature_run_id,id) REFERENCES signature_run(id,scan_id);
CREATE TABLE signature_check (
    run_id uuid NOT NULL,
    scan_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    entry_sequence bigint NOT NULL,
    attempt_id uuid,
    algorithm text NOT NULL DEFAULT 'SHA-256' CHECK (algorithm='SHA-256'),
    check_status text NOT NULL CHECK (check_status IN ('CHECKED_HASH','EXCLUDED_BY_SIZE','HASH_REQUIRED','STALE','READ_ERROR','CATALOG_NOT_CHECKED')),
    match_status text NOT NULL CHECK (match_status IN ('MATCHED','NO_MATCH_IN_CHECKED_CATALOG','UNDETERMINED')),
    checked_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id,entry_id),
    FOREIGN KEY (run_id,scan_id) REFERENCES signature_run(id,scan_id),
    FOREIGN KEY (entry_id,scan_id) REFERENCES scan_entry(id,scan_id),
    FOREIGN KEY (attempt_id,entry_id,scan_id,algorithm) REFERENCES hash_attempt(id,entry_id,scan_id,algorithm),
    CHECK ((check_status='CHECKED_HASH') = (attempt_id IS NOT NULL))
);
CREATE TABLE signature_match (
    run_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    signature_id uuid NOT NULL,
    signature_revision bigint NOT NULL,
    matched_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (run_id,entry_id,signature_id),
    FOREIGN KEY (run_id,entry_id) REFERENCES signature_check(run_id,entry_id),
    FOREIGN KEY (signature_id,signature_revision) REFERENCES signature_revision(signature_id,revision)
);
CREATE INDEX signature_check_page ON signature_check(run_id,entry_sequence);
CREATE INDEX signature_check_history ON signature_check(entry_id,run_id);
CREATE INDEX signature_match_lookup ON signature_match(signature_id,run_id,entry_id);

CREATE TABLE signature_preview (
    id uuid PRIMARY KEY,
    actor text NOT NULL,
    scan_id uuid NOT NULL REFERENCES scan(id),
    catalog_revision bigint NOT NULL,
    evidence_revision bigint NOT NULL,
    entry_cutoff bigint NOT NULL,
    candidate_files bigint NOT NULL,
    candidate_bytes numeric(38,0) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '1 hour',
    job_id uuid REFERENCES job(id)
);
CREATE TABLE signature_import (
    id uuid PRIMARY KEY,
    actor text NOT NULL,
    format text NOT NULL CHECK (format IN ('JSON','CSV')),
    policy text NOT NULL CHECK (policy IN ('REJECT_EXISTING_ID','UPDATE_BY_ID')),
    catalog_revision bigint NOT NULL,
    row_count integer NOT NULL,
    error_count integer NOT NULL,
    state text NOT NULL CHECK (state IN ('VALID','INVALID','APPLIED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '24 hours',
    applied_at timestamptz,
    applied_catalog_revision bigint
);
CREATE TABLE signature_import_row (
    import_id uuid NOT NULL REFERENCES signature_import(id),
    ordinal integer NOT NULL,
    proposed jsonb,
    error text,
    PRIMARY KEY (import_id,ordinal)
);

-- Current derived evidence is a separate relation; manual annotations are untouched.
CREATE VIEW signature_coverage AS
SELECT e.id AS entry_id,s.current_signature_run_id AS run_id,r.catalog_revision,
    r.catalog_revision=clock.revision AS catalog_current,c.checked_at,c.attempt_id,
    CASE WHEN s.evidence_block_code IS NOT NULL OR EXISTS (SELECT 1 FROM observation_validation v WHERE v.entry_id=e.id)
        OR h.active IS FALSE OR (c.attempt_id IS NOT NULL AND h.attempt_id IS DISTINCT FROM c.attempt_id) THEN 'STALE'
        WHEN c.run_id IS NULL OR (c.attempt_id IS NULL AND h.active) THEN 'CATALOG_NOT_CHECKED' ELSE c.check_status END AS check_status,
    CASE WHEN s.evidence_block_code IS NOT NULL OR EXISTS (SELECT 1 FROM observation_validation v WHERE v.entry_id=e.id)
        OR h.active IS FALSE OR (c.attempt_id IS NOT NULL AND h.attempt_id IS DISTINCT FROM c.attempt_id) OR c.run_id IS NULL OR (c.attempt_id IS NULL AND h.active) THEN 'UNDETERMINED'
        ELSE c.match_status END AS match_status
FROM scan_entry e JOIN scan s ON s.id=e.scan_id CROSS JOIN signature_clock clock
LEFT JOIN signature_run r ON r.id=s.current_signature_run_id
LEFT JOIN signature_check c ON c.run_id=r.id AND c.entry_id=e.id
LEFT JOIN accepted_hash h ON h.entry_id=e.id AND h.algorithm='SHA-256';

-- Catalog exports are database-only durable artifacts, independent of any source scan.
-- A batch and checkpoint commit together under the scheduler fencing transaction.
CREATE TABLE signature_export (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    actor text NOT NULL,
    catalog_revision bigint NOT NULL,
    format text NOT NULL CHECK (format IN ('JSON','CSV')),
    state text NOT NULL CHECK (state IN ('QUEUED','BUILDING','PAUSED','READY','CANCELLED','FAILED')),
    after_sequence bigint NOT NULL DEFAULT 0,
    row_count bigint NOT NULL DEFAULT 0,
    byte_count bigint NOT NULL DEFAULT 0,
    max_bytes bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    digest bytea,
    error_code text,
    CHECK ((state='READY') = (digest IS NOT NULL))
);
CREATE TABLE signature_export_chunk (
    export_id uuid NOT NULL REFERENCES signature_export(id),
    ordinal integer NOT NULL,
    content bytea NOT NULL,
    PRIMARY KEY (export_id,ordinal)
);
CREATE INDEX signature_export_queue ON signature_export(sequence) WHERE state IN ('QUEUED','BUILDING');

ALTER TABLE selection ADD COLUMN signature_catalog_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE selection ADD COLUMN signature_run_id uuid;
ALTER TABLE selection ADD FOREIGN KEY (signature_run_id,scan_id) REFERENCES signature_run(id,scan_id);
