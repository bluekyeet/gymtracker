-- Stable identity of this database. Clients compare it across syncs: if it changes (restore from
-- an older backup, fresh volume) they reset their pull cursor and re-push local data.
CREATE TABLE server_meta (
    key TEXT PRIMARY KEY,
    value TEXT NOT NULL
);
INSERT INTO server_meta (key, value) VALUES ('server_id', gen_random_uuid()::text);

-- When a refresh token was rotated. A replay inside a short grace window is a lost response,
-- not theft, so it must not revoke the whole token family.
ALTER TABLE refresh_tokens ADD COLUMN used_at TIMESTAMPTZ;
UPDATE refresh_tokens SET used_at = created_at WHERE used;
CREATE UNIQUE INDEX uq_refresh_tokens_hash ON refresh_tokens (token_hash);
CREATE INDEX idx_refresh_tokens_device ON refresh_tokens (device_id);
