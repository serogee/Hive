-- Deleted account IDs, so unexpired Supabase access tokens cannot save a deleted account again.
CREATE TABLE hive.deleted_accounts (
    account_id TEXT PRIMARY KEY,
    deleted_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
