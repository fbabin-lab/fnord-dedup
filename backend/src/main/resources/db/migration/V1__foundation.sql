CREATE TABLE source_configuration (
    revision varchar(64) PRIMARY KEY CHECK (revision ~ '^[0-9a-f]{64}$'),
    schema_version integer NOT NULL CHECK (schema_version = 1),
    document jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE audit_event (
    id uuid PRIMARY KEY,
    occurred_at timestamptz NOT NULL DEFAULT now(),
    actor varchar(200) NOT NULL,
    action varchar(80) NOT NULL,
    correlation_id uuid NOT NULL,
    details jsonb NOT NULL DEFAULT '{}'::jsonb
);
CREATE INDEX audit_event_time_idx ON audit_event (occurred_at, id);
