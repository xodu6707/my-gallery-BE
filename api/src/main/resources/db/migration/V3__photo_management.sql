CREATE TABLE shoot_sessions (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES auth_users(id),
 title varchar(120) NOT NULL, shoot_date date NOT NULL, cover_photo_id uuid,
 created_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz
);
CREATE INDEX shoot_sessions_owner ON shoot_sessions(owner_id, shoot_date DESC, id DESC) WHERE deleted_at IS NULL;
CREATE TABLE photos (
 id uuid PRIMARY KEY, owner_id uuid NOT NULL REFERENCES auth_users(id),
 session_id uuid NOT NULL REFERENCES shoot_sessions(id), request_id uuid NOT NULL,
 filename varchar(255) NOT NULL, expected_size bigint NOT NULL CHECK(expected_size>0),
 original_key varchar(300) NOT NULL UNIQUE, original_sha256 char(64),
 status varchar(24) NOT NULL CHECK(status IN ('UPLOADING','QUEUED','PROCESSING','READY','FAILED','DELETED')),
 sort_order integer NOT NULL, error_code varchar(50), metadata jsonb,
 thumb_key varchar(300), view_key varchar(300), pipeline_version varchar(40),
 created_at timestamptz NOT NULL DEFAULT now(), deleted_at timestamptz,
 UNIQUE(owner_id, request_id)
);
CREATE INDEX photos_session ON photos(session_id,sort_order,id) WHERE deleted_at IS NULL;
CREATE TABLE processing_jobs (
 photo_id uuid PRIMARY KEY REFERENCES photos(id), attempts integer NOT NULL DEFAULT 0,
 lease_id uuid, lease_until timestamptz, next_at timestamptz NOT NULL DEFAULT now(),
 finished boolean NOT NULL DEFAULT false
);
CREATE INDEX processing_jobs_pending ON processing_jobs(next_at) WHERE NOT finished;
CREATE TABLE storage_garbage (object_key varchar(300) PRIMARY KEY, not_before timestamptz NOT NULL);
