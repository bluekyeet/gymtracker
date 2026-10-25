-- V1: initial schema
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE users (
    id UUID PRIMARY KEY,
    email TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,
    last_seq BIGINT NOT NULL DEFAULT 0,
    unit_preference TEXT NOT NULL DEFAULT 'kg',
    timezone TEXT NOT NULL DEFAULT 'UTC',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE devices (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    name TEXT NOT NULL,
    kind TEXT NOT NULL,
    last_seen TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE exercises (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    name TEXT NOT NULL,
    muscle_group TEXT,
    equipment TEXT,
    is_custom BOOLEAN NOT NULL DEFAULT false,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE splits (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    name TEXT NOT NULL,
    is_active BOOLEAN NOT NULL DEFAULT false,
    rotation_position INT NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE split_days (
    id UUID PRIMARY KEY,
    split_id UUID NOT NULL REFERENCES splits(id),
    name TEXT NOT NULL,
    sort_key DOUBLE PRECISION NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE split_day_exercises (
    id UUID PRIMARY KEY,
    split_day_id UUID NOT NULL REFERENCES split_days(id),
    exercise_id UUID REFERENCES exercises(id),
    sort_key DOUBLE PRECISION NOT NULL DEFAULT 0,
    target_sets INT,
    target_reps INT,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE workout_sessions (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    split_day_id UUID REFERENCES split_days(id),
    day_name TEXT,
    started_at TIMESTAMPTZ NOT NULL,
    ended_at TIMESTAMPTZ,
    notes TEXT,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE workout_exercises (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES workout_sessions(id),
    exercise_id UUID REFERENCES exercises(id),
    exercise_name TEXT NOT NULL,
    sort_key DOUBLE PRECISION NOT NULL DEFAULT 0,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE sets (
    id UUID PRIMARY KEY,
    workout_exercise_id UUID NOT NULL REFERENCES workout_exercises(id),
    set_number INT NOT NULL,
    weight_kg NUMERIC(7,2) NOT NULL,
    reps INT NOT NULL,
    rpe NUMERIC(3,1),
    set_type TEXT NOT NULL DEFAULT 'working',
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

CREATE TABLE body_weights (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    date TIMESTAMPTZ NOT NULL,
    weight_kg NUMERIC(7,2) NOT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

-- sync tables
CREATE TABLE changes (
    id BIGSERIAL PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    seq BIGINT NOT NULL,
    entity TEXT NOT NULL,
    entity_id UUID NOT NULL,
    revision BIGINT NOT NULL,
    deleted BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_changes_user_seq ON changes(user_id, seq);

CREATE TABLE applied_ops (
    op_id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id),
    result TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE refresh_tokens (
    id UUID PRIMARY KEY,
    device_id UUID NOT NULL REFERENCES devices(id),
    token_hash TEXT NOT NULL,
    family_id UUID NOT NULL,
    used BOOLEAN NOT NULL DEFAULT false,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE pairing_codes (
    id UUID PRIMARY KEY,
    user_id UUID REFERENCES users(id),
    code_hash TEXT NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used BOOLEAN NOT NULL DEFAULT false,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- constraints
CREATE UNIQUE INDEX uq_sets_number_per_exercise ON sets (workout_exercise_id, set_number) WHERE deleted_at IS NULL;
