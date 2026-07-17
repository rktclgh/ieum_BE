BEGIN;

DO $$
DECLARE
    grounding_status_constraint_name TEXT;
BEGIN
    SELECT constraint_row.conname
      INTO grounding_status_constraint_name
      FROM pg_constraint constraint_row
      JOIN pg_attribute attribute
        ON attribute.attrelid = constraint_row.conrelid
       AND attribute.attname = 'grounding_status'
       AND attribute.attnum > 0
       AND NOT attribute.attisdropped
     WHERE constraint_row.conrelid = 'public.ai_question_tasks'::regclass
       AND constraint_row.contype = 'c'
       AND constraint_row.conkey = ARRAY[attribute.attnum]::smallint[];

    IF grounding_status_constraint_name IS NULL THEN
        RAISE EXCEPTION 'grounding status constraint is missing from ai_question_tasks';
    END IF;

    EXECUTE format(
        'ALTER TABLE public.ai_question_tasks DROP CONSTRAINT %I',
        grounding_status_constraint_name
    );
END
$$;

ALTER TABLE public.ai_question_tasks
    ADD CONSTRAINT ck_ai_question_tasks_grounding_status
        CHECK (grounding_status IS NULL OR grounding_status IN (
            'grounded',
            'insufficient_evidence',
            'ungrounded'
        ));

ALTER TABLE public.ai_question_tasks
    DROP CONSTRAINT ck_ai_question_tasks_answer_outcome,
    ADD CONSTRAINT ck_ai_question_tasks_answer_outcome
        CHECK (answer_outcome IS NULL OR answer_outcome IN (
            'local_grounded',
            'web_grounded',
            'insufficient_evidence',
            'ungrounded'
        ));

ALTER TABLE public.ai_question_tasks
    DROP CONSTRAINT ck_ai_question_tasks_completed,
    ADD CONSTRAINT ck_ai_question_tasks_completed
        CHECK (status <> 'completed' OR (
            completed_at IS NOT NULL
            AND embedding IS NOT NULL
            AND embedding_model IS NOT NULL
            AND answer_outcome IS NOT NULL
            AND grounding_status IS NOT NULL
            AND (
                (answer_outcome = 'insufficient_evidence' AND answer_id IS NULL)
                OR
                (answer_outcome IN ('local_grounded', 'web_grounded')
                    AND answer_id IS NOT NULL
                    AND generation_provider IS NOT NULL
                    AND generation_model IS NOT NULL
                    AND jsonb_array_length(evidence) > 0)
                OR
                (answer_outcome = 'ungrounded'
                    AND answer_id IS NOT NULL
                    AND generation_provider IS NOT NULL
                    AND generation_model IS NOT NULL
                    AND grounding_status = 'ungrounded'
                    AND jsonb_array_length(evidence) = 0)
            )
        ));

COMMIT;
