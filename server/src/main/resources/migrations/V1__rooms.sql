CREATE TABLE hive.accounts (
    id TEXT PRIMARY KEY,
    display_name TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE hive.rooms (
    id TEXT PRIMARY KEY,
    code TEXT NOT NULL UNIQUE,
    title TEXT NOT NULL,
    host_name TEXT NOT NULL,
    host_account_id TEXT REFERENCES hive.accounts (id),
    mode TEXT NOT NULL,
    admission TEXT NOT NULL,
    status TEXT NOT NULL,
    stack_json TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    deadline_at BIGINT,
    revision BIGINT NOT NULL,
    reveal_policy TEXT NOT NULL,
    question_index INTEGER,
    question_open BOOLEAN NOT NULL,
    question_opened_at BIGINT,
    speed_scoring BOOLEAN NOT NULL,
    time_tiebreak BOOLEAN NOT NULL
);

CREATE INDEX rooms_by_host ON hive.rooms (host_account_id);

CREATE TABLE hive.participants (
    id TEXT PRIMARY KEY,
    room_id TEXT NOT NULL REFERENCES hive.rooms (id) ON DELETE CASCADE,
    display_name TEXT NOT NULL,
    role TEXT NOT NULL,
    account_id TEXT REFERENCES hive.accounts (id),
    token_hash TEXT NOT NULL,
    joined_at BIGINT NOT NULL,
    left_at BIGINT,
    -- Keeps join order.
    sequence BIGINT GENERATED ALWAYS AS IDENTITY
);

CREATE INDEX participants_by_room ON hive.participants (room_id, sequence);
CREATE INDEX participants_by_token ON hive.participants (room_id, token_hash);
CREATE INDEX participants_by_account ON hive.participants (account_id);

CREATE TABLE hive.submissions (
    id TEXT PRIMARY KEY,
    room_id TEXT NOT NULL REFERENCES hive.rooms (id) ON DELETE CASCADE,
    participant_id TEXT NOT NULL REFERENCES hive.participants (id) ON DELETE CASCADE,
    question_id TEXT NOT NULL,
    answer_json TEXT NOT NULL,
    correct BOOLEAN NOT NULL,
    points INTEGER NOT NULL,
    idempotency_key TEXT NOT NULL,
    accepted_at BIGINT NOT NULL,
    response_millis BIGINT,
    speed_bonus_tenths INTEGER NOT NULL,
    -- Keeps the order in which answers arrived.
    sequence BIGINT GENERATED ALWAYS AS IDENTITY,
    UNIQUE (participant_id, question_id),
    UNIQUE (participant_id, idempotency_key)
);

CREATE INDEX submissions_by_room ON hive.submissions (room_id, sequence);

CREATE TABLE hive.attempts (
    room_id TEXT NOT NULL REFERENCES hive.rooms (id) ON DELETE CASCADE,
    participant_id TEXT NOT NULL REFERENCES hive.participants (id) ON DELETE CASCADE,
    question_index INTEGER NOT NULL,
    opened_at BIGINT NOT NULL,
    finished BOOLEAN NOT NULL,
    PRIMARY KEY (room_id, participant_id)
);
