CREATE SEQUENCE hive.sync_changes;

CREATE TABLE hive.sync_stacks (
    account_id TEXT NOT NULL REFERENCES hive.accounts (id) ON DELETE CASCADE,
    stack_id TEXT NOT NULL,
    version BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL,
    -- Null for a deleted stack.
    document TEXT,
    change_seq BIGINT NOT NULL,
    PRIMARY KEY (account_id, stack_id)
);

CREATE INDEX sync_stacks_by_change ON hive.sync_stacks (account_id, change_seq);

CREATE TABLE hive.sync_records (
    account_id TEXT NOT NULL REFERENCES hive.accounts (id) ON DELETE CASCADE,
    kind TEXT NOT NULL,
    id TEXT NOT NULL,
    happened_at BIGINT NOT NULL,
    data TEXT NOT NULL,
    change_seq BIGINT NOT NULL,
    PRIMARY KEY (account_id, kind, id)
);

CREATE INDEX sync_records_by_change ON hive.sync_records (account_id, change_seq);

-- Goes up each time the account's sync data is deleted. No row means 0.
CREATE TABLE hive.sync_accounts (
    account_id TEXT PRIMARY KEY REFERENCES hive.accounts (id) ON DELETE CASCADE,
    generation BIGINT NOT NULL
);
