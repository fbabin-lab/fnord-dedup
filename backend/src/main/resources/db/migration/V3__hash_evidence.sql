-- Existing INVENTORY jobs remain metadata-only historical scans.
ALTER TABLE job DROP CONSTRAINT job_scan_id_key;
ALTER TABLE job DROP CONSTRAINT job_type_check;
ALTER TABLE job DROP CONSTRAINT job_phase_check;
ALTER TABLE job ADD CHECK (type IN ('INVENTORY','SCAN','HASH'));
ALTER TABLE job ADD CHECK (phase IN ('INVENTORY','CANDIDATE_SELECTION','HASHING','ANALYSIS'));
ALTER TABLE job ADD UNIQUE (id,scan_id);
CREATE UNIQUE INDEX scan_inventory_job ON job(scan_id) WHERE type IN ('INVENTORY','SCAN');
ALTER TABLE job ADD COLUMN candidate_files bigint NOT NULL DEFAULT 0;
ALTER TABLE job ADD COLUMN candidate_bytes numeric(38,0) NOT NULL DEFAULT 0;
ALTER TABLE job ADD COLUMN hashed_files bigint NOT NULL DEFAULT 0;
ALTER TABLE job ADD COLUMN reused_files bigint NOT NULL DEFAULT 0;
ALTER TABLE job ADD COLUMN physical_bytes_read numeric(38,0) NOT NULL DEFAULT 0;
ALTER TABLE job ADD COLUMN useful_bytes_hashed numeric(38,0) NOT NULL DEFAULT 0;
ALTER TABLE scan ADD COLUMN evidence_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE scan ADD COLUMN current_analysis_id uuid;
ALTER TABLE scan ADD COLUMN evidence_block_code text;
ALTER TABLE scan_entry ADD UNIQUE (id,scan_id);
ALTER TABLE scan_entry ADD UNIQUE (id,scan_id,size_bytes);
ALTER TABLE scan_entry ADD UNIQUE (id,location_id);
ALTER TABLE work_item DROP CONSTRAINT work_item_kind_check;
ALTER TABLE work_item ADD CHECK (kind IN ('DIRECTORY','SELECT_CANDIDATES','HASH','GROUP'));
ALTER TABLE work_item ADD COLUMN entry_id uuid;
ALTER TABLE work_item ADD COLUMN payload jsonb NOT NULL DEFAULT '{}';
ALTER TABLE work_item ADD FOREIGN KEY (entry_id,location_id) REFERENCES scan_entry(id,location_id);
ALTER TABLE work_item ADD CHECK ((kind='HASH') = (entry_id IS NOT NULL));
ALTER TABLE observation_validation DROP CONSTRAINT observation_validation_outcome_check;
ALTER TABLE observation_validation ADD CHECK (outcome IN ('UNSTABLE','HASH_CHANGED','HASH_CONFLICT'));

CREATE TABLE candidate_size (
    scan_id uuid NOT NULL REFERENCES scan(id),
    size_bytes bigint NOT NULL CHECK (size_bytes>=0),
    path_count bigint NOT NULL CHECK (path_count>=2),
    PRIMARY KEY (scan_id,size_bytes)
);
CREATE TABLE hash_attempt (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    scan_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    job_id uuid NOT NULL,
    work_id uuid NOT NULL REFERENCES work_item(id),
    lease_token bigint NOT NULL,
    algorithm text NOT NULL DEFAULT 'SHA-256' CHECK (algorithm='SHA-256'),
    reasons jsonb NOT NULL,
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    completed_at timestamptz,
    bytes_read numeric(38,0) NOT NULL DEFAULT 0 CHECK (bytes_read>=0),
    outcome text NOT NULL DEFAULT 'READING' CHECK (outcome IN ('READING','ACCEPTED','FAILED','INTERRUPTED','STOPPED','CONFLICT')),
    pre_fingerprint jsonb,
    post_fingerprint jsonb,
    digest bytea,
    error_code text,
    error_detail text,
    FOREIGN KEY (entry_id,scan_id) REFERENCES scan_entry(id,scan_id),
    FOREIGN KEY (job_id,scan_id) REFERENCES job(id,scan_id),
    UNIQUE (work_id,lease_token),
    UNIQUE (id,entry_id,scan_id,algorithm),
    UNIQUE (id,entry_id,scan_id,algorithm,digest),
    CHECK ((outcome='ACCEPTED') = (digest IS NOT NULL)),
    CHECK (digest IS NULL OR octet_length(digest)=32),
    CHECK ((outcome='READING') = (completed_at IS NULL))
);
CREATE INDEX hash_attempt_entry ON hash_attempt(entry_id,sequence);
CREATE INDEX hash_attempt_open ON hash_attempt(job_id) WHERE outcome='READING';
CREATE TABLE accepted_hash (
    entry_id uuid NOT NULL,
    scan_id uuid NOT NULL,
    algorithm text NOT NULL CHECK (algorithm='SHA-256'),
    attempt_id uuid NOT NULL,
    active boolean NOT NULL DEFAULT true,
    invalidated_at timestamptz,
    invalidation_code text,
    PRIMARY KEY (entry_id,algorithm),
    FOREIGN KEY (attempt_id,entry_id,scan_id,algorithm) REFERENCES hash_attempt(id,entry_id,scan_id,algorithm),
    CHECK (active = (invalidated_at IS NULL))
);
CREATE INDEX accepted_scan ON accepted_hash(scan_id,entry_id) WHERE active;
CREATE INDEX hash_digest ON hash_attempt(algorithm,digest) WHERE outcome='ACCEPTED';
CREATE TABLE analysis_revision (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    scan_id uuid NOT NULL REFERENCES scan(id),
    job_id uuid NOT NULL,
    evidence_revision bigint NOT NULL,
    state text NOT NULL CHECK (state IN ('BUILDING','PUBLISHED','ABANDONED')),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    published_at timestamptz,
    FOREIGN KEY (job_id,scan_id) REFERENCES job(id,scan_id),
    UNIQUE (id,scan_id)
);
ALTER TABLE scan ADD FOREIGN KEY (current_analysis_id,id) REFERENCES analysis_revision(id,scan_id);
CREATE TABLE duplicate_group (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    analysis_id uuid NOT NULL,
    scan_id uuid NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes>=0),
    algorithm text NOT NULL DEFAULT 'SHA-256' CHECK (algorithm='SHA-256'),
    digest bytea NOT NULL CHECK (octet_length(digest)=32),
    path_count bigint NOT NULL CHECK (path_count>=2),
    object_count bigint,
    identity_status text NOT NULL,
    FOREIGN KEY (analysis_id,scan_id) REFERENCES analysis_revision(id,scan_id),
    UNIQUE (analysis_id,size_bytes,digest),
    UNIQUE (id,analysis_id,scan_id,size_bytes,algorithm,digest)
);
-- A captured input is also the group member once group_id is assigned. Exact
-- attempt/scan/group fingerprint FKs prevent cross-scan or mixed-revision members.
CREATE TABLE analysis_input (
    analysis_id uuid NOT NULL,
    scan_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    entry_sequence bigint NOT NULL,
    attempt_id uuid NOT NULL,
    algorithm text NOT NULL CHECK (algorithm='SHA-256'),
    size_bytes bigint NOT NULL,
    digest bytea NOT NULL CHECK (octet_length(digest)=32),
    group_id uuid,
    PRIMARY KEY (analysis_id,entry_id),
    FOREIGN KEY (analysis_id,scan_id) REFERENCES analysis_revision(id,scan_id),
    FOREIGN KEY (entry_id,scan_id,size_bytes) REFERENCES scan_entry(id,scan_id,size_bytes),
    FOREIGN KEY (attempt_id,entry_id,scan_id,algorithm,digest) REFERENCES hash_attempt(id,entry_id,scan_id,algorithm,digest),
    FOREIGN KEY (group_id,analysis_id,scan_id,size_bytes,algorithm,digest)
        REFERENCES duplicate_group(id,analysis_id,scan_id,size_bytes,algorithm,digest)
);
CREATE INDEX analysis_input_page ON analysis_input(analysis_id,entry_sequence);
CREATE INDEX analysis_input_digest ON analysis_input(analysis_id,size_bytes,digest);
CREATE INDEX group_members ON analysis_input(group_id,entry_sequence);
CREATE INDEX group_page ON duplicate_group(analysis_id,sequence);
