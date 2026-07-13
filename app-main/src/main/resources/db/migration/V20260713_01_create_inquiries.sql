DO $$
BEGIN
	IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'inquiry_status') THEN
		CREATE TYPE inquiry_status AS ENUM ('pending', 'answered');
	END IF;
END $$;

CREATE TABLE IF NOT EXISTS inquiries (
	inquiry_id  bigserial PRIMARY KEY,
	user_id     bigint         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
	title       varchar(200)   NOT NULL,
	content     text           NOT NULL,
	status      inquiry_status NOT NULL DEFAULT 'pending',
	answer      text,
	answered_by bigint         REFERENCES users (user_id),
	created_at  timestamptz    NOT NULL DEFAULT now(),
	answered_at timestamptz
);

CREATE INDEX IF NOT EXISTS idx_inquiries_user ON inquiries (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_inquiries_status ON inquiries (status, created_at DESC);
