CREATE TABLE IF NOT EXISTS user_sanctions (
    sanction_id  bigserial PRIMARY KEY,
    user_id      bigint      NOT NULL REFERENCES users (user_id),
    type         varchar(30) NOT NULL,
    reason       text        NOT NULL,
    ends_at      timestamptz,
    created_by   bigint      NOT NULL REFERENCES users (user_id),
    created_at   timestamptz NOT NULL DEFAULT now(),
    released_at  timestamptz,
    released_by  bigint      REFERENCES users (user_id),
    CONSTRAINT chk_user_sanctions_ends_at CHECK (
        (type = 'temporary' AND ends_at IS NOT NULL)
        OR (type = 'permanent' AND ends_at IS NULL)
    )
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_user_sanctions_active
    ON user_sanctions (user_id) WHERE released_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_user_sanctions_user_created
    ON user_sanctions (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_user_sanctions_expiry
    ON user_sanctions (ends_at) WHERE released_at IS NULL AND type = 'temporary';
