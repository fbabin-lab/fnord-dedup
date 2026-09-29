CREATE TABLE source_root (
    id uuid NOT NULL,
    source_instance_id uuid NOT NULL,
    configuration_revision text NOT NULL REFERENCES source_configuration(revision),
    snapshot jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (id, source_instance_id)
);

CREATE TABLE file_location (
    id uuid PRIMARY KEY,
    source_id uuid NOT NULL,
    source_instance_id uuid NOT NULL,
    parent_id uuid,
    name_bytes bytea NOT NULL CHECK (octet_length(name_bytes) <= 255),
    relative_path_bytes bytea NOT NULL,
    display_name text NOT NULL,
    display_path text NOT NULL,
    extension text,
    first_seen_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    FOREIGN KEY (source_id, source_instance_id) REFERENCES source_root(id, source_instance_id),
    UNIQUE (id, source_id, source_instance_id),
    FOREIGN KEY (parent_id, source_id, source_instance_id) REFERENCES file_location(id, source_id, source_instance_id),
    UNIQUE NULLS NOT DISTINCT (source_id, source_instance_id, parent_id, name_bytes),
    CHECK ((parent_id IS NULL AND octet_length(name_bytes) = 0 AND octet_length(relative_path_bytes) = 0)
        OR (parent_id IS NOT NULL AND octet_length(name_bytes) > 0 AND octet_length(relative_path_bytes) > 0))
);
CREATE INDEX location_parent ON file_location(parent_id, id);

CREATE TABLE scan (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    name text NOT NULL CHECK (length(name) BETWEEN 1 AND 200),
    configuration_revision text NOT NULL REFERENCES source_configuration(revision),
    options jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    inventory_frozen_at timestamptz
);

CREATE TABLE job (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    scan_id uuid NOT NULL UNIQUE REFERENCES scan(id),
    type text NOT NULL CHECK (type = 'INVENTORY'),
    phase text NOT NULL CHECK (phase = 'INVENTORY'),
    state text NOT NULL CHECK (state IN ('QUEUED','RUNNING','PAUSE_REQUESTED','PAUSED','CANCEL_REQUESTED','CANCELLED','INTERRUPTED','COMPLETED','COMPLETED_WITH_ERRORS','FAILED')),
    version bigint NOT NULL DEFAULT 0,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    started_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    heartbeat_at timestamptz,
    checkpoint_at timestamptz,
    current_source_id uuid,
    current_location_id uuid REFERENCES file_location(id),
    discovered_entries bigint NOT NULL DEFAULT 0,
    discovered_files bigint NOT NULL DEFAULT 0,
    discovered_directories bigint NOT NULL DEFAULT 0,
    discovered_bytes numeric(38,0) NOT NULL DEFAULT 0,
    error_count bigint NOT NULL DEFAULT 0,
    skipped_entries bigint NOT NULL DEFAULT 0,
    pending_work bigint NOT NULL DEFAULT 0,
    completed_work bigint NOT NULL DEFAULT 0,
    block_code text,
    lease_seconds integer NOT NULL DEFAULT 60 CHECK (lease_seconds = 60),
    heartbeat_seconds integer NOT NULL DEFAULT 10 CHECK (heartbeat_seconds = 10)
);
CREATE INDEX job_queue ON job(state, sequence);
CREATE UNIQUE INDEX single_active_job ON job((true)) WHERE state IN ('RUNNING','PAUSE_REQUESTED','CANCEL_REQUESTED');

CREATE TABLE scan_source (
    scan_id uuid NOT NULL REFERENCES scan(id),
    source_id uuid NOT NULL,
    source_instance_id uuid NOT NULL,
    root_location_id uuid NOT NULL REFERENCES file_location(id),
    snapshot jsonb NOT NULL,
    root_identity jsonb,
    coverage text NOT NULL DEFAULT 'PARTIAL' CHECK (coverage IN ('COMPLETE','PARTIAL','UNAVAILABLE','EXCLUDED_BY_POLICY')),
    PRIMARY KEY (scan_id, source_id),
    UNIQUE (scan_id, source_id, source_instance_id),
    FOREIGN KEY (source_id, source_instance_id) REFERENCES source_root(id, source_instance_id)
);

CREATE TABLE scan_entry (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    scan_id uuid NOT NULL REFERENCES scan(id),
    location_id uuid NOT NULL REFERENCES file_location(id),
    source_id uuid NOT NULL,
    source_instance_id uuid NOT NULL,
    entry_type text NOT NULL CHECK (entry_type IN ('DIRECTORY','REGULAR','SYMLINK','SPECIAL','UNKNOWN')),
    size_bytes bigint CHECK (size_bytes >= 0),
    metadata_mask integer,
    mode integer,
    inode numeric(20,0),
    mount_id numeric(20,0),
    device_major bigint,
    device_minor bigint,
    mtime_seconds bigint,
    mtime_nanos integer CHECK (mtime_nanos BETWEEN 0 AND 999999999),
    ctime_seconds bigint,
    ctime_nanos integer CHECK (ctime_nanos BETWEEN 0 AND 999999999),
    birth_seconds bigint,
    birth_nanos integer CHECK (birth_nanos BETWEEN 0 AND 999999999),
    mtime timestamptz,
    ctime timestamptz,
    link_count numeric(20,0),
    blocks numeric(20,0),
    uid bigint,
    gid bigint,
    filesystem_type text,
    symlink_target bytea,
    discovery_status text NOT NULL,
    directory_coverage text CHECK (directory_coverage IN ('COMPLETE','PARTIAL','UNAVAILABLE','EXCLUDED_BY_POLICY')),
    fingerprint jsonb NOT NULL,
    observed_at timestamptz NOT NULL,
    UNIQUE (scan_id, location_id),
    FOREIGN KEY (scan_id, source_id, source_instance_id) REFERENCES scan_source(scan_id, source_id, source_instance_id),
    FOREIGN KEY (location_id, source_id, source_instance_id) REFERENCES file_location(id, source_id, source_instance_id)
);
CREATE INDEX entry_size ON scan_entry(scan_id, entry_type, size_bytes);
CREATE INDEX entry_page ON scan_entry(scan_id, sequence);
CREATE INDEX entry_mtime ON scan_entry(scan_id, mtime, id);

CREATE TABLE observation_validation (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id uuid NOT NULL REFERENCES scan_entry(id),
    outcome text NOT NULL CHECK (outcome = 'UNSTABLE'),
    observed_fingerprint jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (entry_id, outcome)
);

CREATE TABLE work_item (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    job_id uuid NOT NULL REFERENCES job(id),
    location_id uuid NOT NULL REFERENCES file_location(id),
    kind text NOT NULL CHECK (kind = 'DIRECTORY'),
    state text NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','LEASED','DONE','ERROR','CANCELLED')),
    attempts integer NOT NULL DEFAULT 0,
    lease_owner uuid,
    lease_token bigint NOT NULL DEFAULT 0,
    lease_expires_at timestamptz,
    checkpoint_at timestamptz,
    outcome text,
    unresolved_children bigint NOT NULL DEFAULT 0 CHECK (unresolved_children >= 0),
    subtree_resolved boolean NOT NULL DEFAULT false,
    has_issues boolean NOT NULL DEFAULT false,
    UNIQUE (job_id, kind, location_id)
);
CREATE INDEX work_queue ON work_item(job_id, state, sequence);
CREATE INDEX work_expiry ON work_item(lease_expires_at) WHERE state = 'LEASED';

CREATE TABLE job_event (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES job(id),
    event_type text NOT NULL,
    actor text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    details jsonb NOT NULL DEFAULT '{}'
);
CREATE INDEX job_event_page ON job_event(job_id, id);

CREATE TABLE job_error (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    job_id uuid NOT NULL REFERENCES job(id),
    location_id uuid NOT NULL REFERENCES file_location(id),
    code text NOT NULL,
    detail text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (job_id, location_id, code)
);
CREATE INDEX job_error_page ON job_error(job_id, id);

CREATE TABLE idempotency_record (
    actor text NOT NULL,
    endpoint text NOT NULL,
    request_key text NOT NULL CHECK (length(request_key) BETWEEN 1 AND 128),
    payload_hash text NOT NULL,
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (actor, endpoint, request_key)
);

-- A DB lease is a coordination lock. Every claim/result transaction checks this
-- owner + epoch under FOR UPDATE, including after a long filesystem syscall.
CREATE TABLE scheduler_lock (
    id integer PRIMARY KEY CHECK (id = 1),
    owner uuid,
    token bigint NOT NULL DEFAULT 0,
    expires_at timestamptz NOT NULL DEFAULT '-infinity'
);
INSERT INTO scheduler_lock(id) VALUES (1);

-- Short global lock for admission/idempotency; never held during filesystem I/O.
CREATE TABLE job_admission (id integer PRIMARY KEY CHECK (id = 1));
INSERT INTO job_admission(id) VALUES (1);
