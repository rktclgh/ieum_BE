-- One-shot v16 -> v17 upgrade. Add the immutable query-analysis checkpoint version.
BEGIN;

ALTER TABLE public.ai_question_tasks
    ADD COLUMN analysis_version VARCHAR(80);

ALTER TABLE public.ai_question_tasks
    ADD CONSTRAINT ck_ai_question_tasks_analysis_version
    CHECK (analysis_version IS NULL OR btrim(analysis_version) <> '') NOT VALID;

ALTER TABLE public.ai_question_tasks
    VALIDATE CONSTRAINT ck_ai_question_tasks_analysis_version;

COMMIT;
