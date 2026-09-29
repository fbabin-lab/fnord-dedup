-- A failed recheck can invalidate a baseline hash without adding observation_validation.
-- Check the baseline observation's live validity separately from its historical digest.
-- No hash is required for metadata-only notes, and a healthy same-content rehash does
-- not invalidate notes merely because its accepted-attempt pointer changed.
-- Replace the view in place: existing rows, V1-V5 checksums and dependent views remain.
CREATE OR REPLACE VIEW observation_facts AS
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
        OR EXISTS(SELECT 1 FROM observation_validation v WHERE v.entry_id IN (e.id,base.id)) OR h.active IS FALSE OR baseline_hash.active IS FALSE
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
LEFT JOIN accepted_hash baseline_hash ON baseline_hash.entry_id=base.id AND baseline_hash.algorithm='SHA-256'
LEFT JOIN scan baseline_scan ON baseline_scan.id=base.scan_id;

-- Existing query tokens must not silently reuse the pre-fix warning/filter semantics.
UPDATE search_clock SET revision=revision+1 WHERE id=1;
