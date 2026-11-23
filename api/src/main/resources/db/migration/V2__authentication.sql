CREATE TABLE auth_users (
 id uuid PRIMARY KEY, email varchar(254) NOT NULL UNIQUE,
 password_hash varchar(512) NOT NULL, version integer NOT NULL DEFAULT 1,
 created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE auth_tokens (
 digest char(64) PRIMARY KEY, email varchar(254) NOT NULL,
 purpose varchar(16) NOT NULL CHECK (purpose IN ('signup','reset')),
 expires_at timestamptz NOT NULL
);
CREATE INDEX auth_tokens_expiry ON auth_tokens(expires_at);
CREATE TABLE auth_rate_limits (bucket char(64) PRIMARY KEY, hits integer NOT NULL, expires_at timestamptz NOT NULL);
CREATE TABLE spring_session (
 primary_id char(36) PRIMARY KEY, session_id char(36) NOT NULL,
 creation_time bigint NOT NULL, last_access_time bigint NOT NULL,
 max_inactive_interval integer NOT NULL, expiry_time bigint NOT NULL,
 principal_name varchar(100)
);
CREATE UNIQUE INDEX spring_session_ix1 ON spring_session(session_id);
CREATE INDEX spring_session_ix2 ON spring_session(expiry_time);
CREATE INDEX spring_session_ix3 ON spring_session(principal_name);
CREATE TABLE spring_session_attributes (
 session_primary_id char(36) NOT NULL REFERENCES spring_session(primary_id) ON DELETE CASCADE,
 attribute_name varchar(200) NOT NULL, attribute_bytes bytea NOT NULL,
 PRIMARY KEY(session_primary_id, attribute_name)
);
