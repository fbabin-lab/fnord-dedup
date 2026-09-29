ALTER TABLE scan ADD COLUMN query_revision bigint NOT NULL DEFAULT 0;
ALTER TABLE scan ADD COLUMN candidates_frozen_at timestamptz;
UPDATE scan s SET candidates_frozen_at=s.inventory_frozen_at
FROM job j WHERE j.scan_id=s.id AND j.type='SCAN' AND j.phase IN ('HASHING','ANALYSIS');

-- Reads of this clock are MVCC-only, never row-locked by queries. Workers keep scan -> clock order.
CREATE TABLE search_clock (id integer PRIMARY KEY CHECK (id=1), revision bigint NOT NULL DEFAULT 0);
INSERT INTO search_clock(id) VALUES (1);
CREATE FUNCTION advance_search_clock() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    UPDATE search_clock SET revision=revision+1 WHERE id=1;
    RETURN NEW;
END;
$$;
CREATE TRIGGER scan_query_changed AFTER UPDATE OF query_revision,evidence_revision,current_analysis_id ON scan
    FOR EACH ROW EXECUTE FUNCTION advance_search_clock();

CREATE TABLE annotation_clock (id integer PRIMARY KEY CHECK (id=1), revision bigint NOT NULL DEFAULT 0);
INSERT INTO annotation_clock(id) VALUES (1);
CREATE TABLE tag (
    id uuid PRIMARY KEY,
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    label text NOT NULL CHECK (length(label) BETWEEN 1 AND 64),
    lookup_key text NOT NULL UNIQUE,
    version bigint NOT NULL DEFAULT 1 CHECK (version>0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE file_annotation (
    location_id uuid PRIMARY KEY REFERENCES file_location(id),
    memo text NOT NULL DEFAULT '' CHECK (length(memo)<=20000),
    review_state text NOT NULL DEFAULT 'UNREVIEWED' CHECK (review_state IN ('UNREVIEWED','REVIEWED','KEEP','REMOVAL_REVIEW')),
    version bigint NOT NULL CHECK (version>0),
    baseline_entry_id uuid NOT NULL,
    baseline_attempt_id uuid REFERENCES hash_attempt(id),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_by text NOT NULL,
    FOREIGN KEY (baseline_entry_id,location_id) REFERENCES scan_entry(id,location_id)
);
CREATE TABLE file_annotation_tag (
    location_id uuid NOT NULL REFERENCES file_annotation(location_id),
    tag_id uuid NOT NULL REFERENCES tag(id),
    PRIMARY KEY (location_id,tag_id)
);
CREATE INDEX manual_tag_lookup ON file_annotation_tag(tag_id,location_id);
CREATE INDEX annotation_review ON file_annotation(review_state,location_id);
CREATE INDEX observation_history ON scan_entry(location_id,sequence);
CREATE INDEX scan_job_history ON job(scan_id,sequence);
CREATE INDEX hash_pending_entry ON work_item(entry_id,state) WHERE entry_id IS NOT NULL;
CREATE INDEX accepted_hex_lookup ON hash_attempt((encode(digest,'hex')) text_pattern_ops) WHERE outcome='ACCEPTED';

CREATE TABLE selection (
    id uuid PRIMARY KEY,
    actor text NOT NULL,
    scan_id uuid NOT NULL REFERENCES scan(id),
    analysis_id uuid,
    evidence_revision bigint NOT NULL,
    query_revision bigint NOT NULL,
    annotation_revision bigint NOT NULL,
    entry_cutoff bigint NOT NULL,
    query_snapshot jsonb NOT NULL,
    selected_count integer NOT NULL CHECK (selected_count BETWEEN 1 AND 500),
    unknown_sizes integer NOT NULL CHECK (unknown_sizes BETWEEN 0 AND 500),
    selected_bytes numeric(38,0) NOT NULL CHECK (selected_bytes>=0),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    applied_at timestamptz,
    expires_at timestamptz NOT NULL DEFAULT clock_timestamp()+interval '24 hours',
    FOREIGN KEY (analysis_id,scan_id) REFERENCES analysis_revision(id,scan_id),
    UNIQUE (id,scan_id)
);
CREATE TABLE selection_member (
    selection_id uuid NOT NULL,
    scan_id uuid NOT NULL,
    entry_id uuid NOT NULL,
    location_id uuid NOT NULL,
    ordinal integer NOT NULL CHECK (ordinal BETWEEN 1 AND 500),
    annotation_version bigint NOT NULL,
    annotation_snapshot jsonb NOT NULL,
    accepted_attempt_id uuid,
    algorithm text NOT NULL DEFAULT 'SHA-256' CHECK (algorithm='SHA-256'),
    was_stale boolean NOT NULL,
    PRIMARY KEY (selection_id,entry_id),
    UNIQUE (selection_id,ordinal),
    FOREIGN KEY (selection_id,scan_id) REFERENCES selection(id,scan_id),
    FOREIGN KEY (entry_id,scan_id) REFERENCES scan_entry(id,scan_id),
    FOREIGN KEY (entry_id,location_id) REFERENCES scan_entry(id,location_id),
    FOREIGN KEY (accepted_attempt_id,entry_id,scan_id,algorithm) REFERENCES hash_attempt(id,entry_id,scan_id,algorithm)
);
CREATE INDEX selection_expiry ON selection(expires_at);

-- Compute invalid groups once as a relation, not a member scan for every result row.
CREATE VIEW current_group_state AS
SELECT g.id,g.scan_id,g.analysis_id,
    CASE WHEN s.evidence_block_code IS NOT NULL OR bad.group_id IS NOT NULL THEN 'STALE' ELSE 'DUPLICATE' END AS state
FROM duplicate_group g JOIN scan s ON s.current_analysis_id=g.analysis_id
LEFT JOIN (
    SELECT DISTINCT i.group_id FROM analysis_input i JOIN scan s ON s.current_analysis_id=i.analysis_id
    LEFT JOIN accepted_hash h ON h.entry_id=i.entry_id AND h.algorithm=i.algorithm
    WHERE i.group_id IS NOT NULL AND (h.active IS DISTINCT FROM true OR h.attempt_id IS DISTINCT FROM i.attempt_id
        OR EXISTS(SELECT 1 FROM observation_validation v WHERE v.entry_id=i.entry_id))
) bad ON bad.group_id=g.id;

CREATE VIEW observation_facts AS
SELECT e.*,l.parent_id,l.display_name,l.display_path,l.name_bytes,l.relative_path_bytes,l.extension,
    s.evidence_block_code,s.candidates_frozen_at,s.options->>'inventoryOnly' AS inventory_only,
    EXISTS(SELECT 1 FROM observation_validation v WHERE v.entry_id=e.id) AS unstable,
    EXISTS(SELECT 1 FROM candidate_size cs WHERE cs.scan_id=e.scan_id AND cs.size_bytes=e.size_bytes) AS candidate,
    EXISTS(SELECT 1 FROM work_item w JOIN job j ON j.id=w.job_id WHERE w.entry_id=e.id AND w.state IN ('READY','LEASED')
        AND j.state NOT IN ('COMPLETED','COMPLETED_WITH_ERRORS','CANCELLED','FAILED')) AS hash_pending,
    h.active AS hash_active,h.attempt_id AS accepted_attempt_id,a.digest,a.completed_at AS hash_completed_at,
    EXISTS(SELECT 1 FROM hash_attempt ha WHERE ha.entry_id=e.id) AS hash_attempted,
    (e.discovery_status NOT IN ('OBSERVED','EXCLUDED_BY_POLICY') OR EXISTS(SELECT 1 FROM job_error er JOIN job j ON j.id=er.job_id WHERE j.scan_id=e.scan_id AND er.location_id=e.location_id)) AS has_error,
    i.group_id AS duplicate_group_id,coalesce(gs.state,'NOT_GROUPED') AS duplicate_state,
    coalesce(n.memo,'') AS memo,coalesce(n.review_state,'UNREVIEWED') AS review_state,coalesce(n.version,0) AS annotation_version,
    n.baseline_entry_id,n.baseline_attempt_id,n.updated_at AS annotation_updated_at,n.updated_by AS annotation_updated_by,
    (n.location_id IS NOT NULL AND (e.fingerprint IS DISTINCT FROM base.fingerprint OR s.evidence_block_code IS NOT NULL OR baseline_scan.evidence_block_code IS NOT NULL
        OR EXISTS(SELECT 1 FROM observation_validation v WHERE v.entry_id IN (e.id,base.id)) OR h.active IS FALSE
        OR (ba.digest IS NOT NULL AND a.digest IS NOT NULL AND ba.digest<>a.digest))) AS annotations_need_review,
    coalesce((SELECT jsonb_agg(jsonb_build_object('id',t.id,'label',t.label,'version',t.version::text) ORDER BY t.lookup_key,t.id)
        FROM file_annotation_tag nt JOIN tag t ON t.id=nt.tag_id WHERE nt.location_id=l.id),'[]'::jsonb) AS manual_tags
FROM scan_entry e JOIN file_location l ON l.id=e.location_id JOIN scan s ON s.id=e.scan_id
LEFT JOIN accepted_hash h ON h.entry_id=e.id AND h.algorithm='SHA-256'
LEFT JOIN hash_attempt a ON a.id=h.attempt_id
LEFT JOIN analysis_input i ON i.analysis_id=s.current_analysis_id AND i.entry_id=e.id
LEFT JOIN current_group_state gs ON gs.id=i.group_id
LEFT JOIN file_annotation n ON n.location_id=l.id
LEFT JOIN scan_entry base ON base.id=n.baseline_entry_id
LEFT JOIN hash_attempt ba ON ba.id=n.baseline_attempt_id
LEFT JOIN scan baseline_scan ON baseline_scan.id=base.scan_id;

CREATE VIEW observation_search AS
SELECT f.*,
    (evidence_block_code IS NOT NULL OR unstable OR hash_active IS FALSE) AS stale,
    CASE WHEN entry_type<>'REGULAR' OR discovery_status<>'OBSERVED' THEN 'INELIGIBLE'
        WHEN evidence_block_code IS NOT NULL OR unstable OR hash_active IS FALSE THEN 'STALE'
        WHEN hash_active THEN 'ACCEPTED' WHEN hash_pending THEN 'PENDING' WHEN hash_attempted THEN 'FAILED'
        WHEN candidates_frozen_at IS NOT NULL AND inventory_only IS DISTINCT FROM 'true' AND NOT candidate THEN 'NOT_REQUESTED_UNIQUE_SIZE'
        ELSE 'NOT_REQUESTED' END AS hash_status
FROM observation_facts f;
