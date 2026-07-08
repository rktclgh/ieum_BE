ALTER TABLE users
	ADD COLUMN IF NOT EXISTS profile_file_id UUID;

CREATE INDEX IF NOT EXISTS idx_users_profile_file_id
	ON users (profile_file_id);

DO $$
BEGIN
	IF NOT EXISTS (
		SELECT 1
		FROM pg_constraint
		WHERE conname = 'fk_users_profile_file_id'
	) THEN
		ALTER TABLE users
			ADD CONSTRAINT fk_users_profile_file_id
			FOREIGN KEY (profile_file_id)
			REFERENCES files (file_id)
			ON DELETE SET NULL;
	END IF;
END $$;
