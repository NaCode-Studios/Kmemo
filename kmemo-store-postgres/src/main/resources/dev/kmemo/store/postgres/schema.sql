-- Canonical schema for kmemo-store-postgres.
--
-- PostgresStore creates this automatically on first use, so you only need this file if you provision
-- the table yourself (managed migrations, least-privilege runtime role, etc.). Replace `kmemo_cache`
-- with your table name if you pass a custom one to the PostgresStore constructor.
--
-- Requires the pgvector extension (https://github.com/pgvector/pgvector).

CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE IF NOT EXISTS kmemo_cache (
    id          text PRIMARY KEY,
    scope       text NOT NULL,
    prompt      text NOT NULL,
    response    text NOT NULL,
    embedding   vector NOT NULL,          -- dimension-unconstrained; one model per store in practice
    created_at  timestamptz NOT NULL,
    expires_at  timestamptz,              -- NULL means the entry never expires
    metadata    jsonb NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS kmemo_cache_scope_idx      ON kmemo_cache (scope);
CREATE INDEX IF NOT EXISTS kmemo_cache_expires_at_idx ON kmemo_cache (expires_at);

-- Optional: once your embedding dimension is fixed, an approximate index makes search scale past an
-- exact scan. Pick the dimension and (for HNSW) the operator class for your distance metric:
--
--   ALTER TABLE kmemo_cache ALTER COLUMN embedding TYPE vector(1536);
--   CREATE INDEX kmemo_cache_embedding_idx ON kmemo_cache
--       USING hnsw (embedding vector_cosine_ops);
