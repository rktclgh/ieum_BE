DO $$
DECLARE
	answer_author_fk_name text;
BEGIN
	SELECT constraint_info.conname
	INTO answer_author_fk_name
	FROM pg_constraint constraint_info
	JOIN pg_attribute attribute_info
		ON attribute_info.attrelid = constraint_info.conrelid
		AND attribute_info.attnum = ANY (constraint_info.conkey)
	WHERE constraint_info.conrelid = 'inquiries'::regclass
		AND constraint_info.confrelid = 'users'::regclass
		AND constraint_info.contype = 'f'
		AND attribute_info.attname = 'answered_by';

	IF answer_author_fk_name IS NOT NULL THEN
		EXECUTE format('ALTER TABLE inquiries DROP CONSTRAINT %I', answer_author_fk_name);
	END IF;

	ALTER TABLE inquiries
		ADD CONSTRAINT inquiries_answered_by_fkey
		FOREIGN KEY (answered_by) REFERENCES users (user_id) ON DELETE SET NULL;
END $$;
