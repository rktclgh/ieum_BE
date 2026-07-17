BEGIN;

DO $$
DECLARE
    constraint_name TEXT;
    completed_constraint_definition TEXT;
BEGIN
    SELECT pg_get_constraintdef(constraint_row.oid)
      INTO completed_constraint_definition
      FROM pg_constraint constraint_row
     WHERE constraint_row.conrelid = 'public.ai_question_tasks'::regclass
       AND constraint_row.conname = 'ck_ai_question_tasks_completed';

    IF completed_constraint_definition IS NULL
        OR lower(completed_constraint_definition) NOT LIKE '%prompt_version is not null%'
    THEN
        IF completed_constraint_definition IS NOT NULL THEN
            ALTER TABLE public.ai_question_tasks
                DROP CONSTRAINT ck_ai_question_tasks_completed;
        END IF;

        ALTER TABLE public.ai_question_tasks
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
                            AND prompt_version IS NOT NULL
                            AND grounding_status = 'ungrounded'
                            AND jsonb_array_length(evidence) = 0)
                    )
                )) NOT VALID;
    END IF;

    FOREACH constraint_name IN ARRAY ARRAY[
        'ck_ai_question_tasks_grounding_status',
        'ck_ai_question_tasks_answer_outcome',
        'ck_ai_question_tasks_completed'
    ]
    LOOP
        IF EXISTS (
            SELECT 1
              FROM pg_constraint
             WHERE conrelid = 'public.ai_question_tasks'::regclass
               AND conname = constraint_name
               AND NOT convalidated
        ) THEN
            EXECUTE format(
                'ALTER TABLE public.ai_question_tasks VALIDATE CONSTRAINT %I',
                constraint_name
            );
        END IF;
    END LOOP;
END
$$;

COMMIT;
