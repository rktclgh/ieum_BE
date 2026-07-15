-- Run after v23. CREATE/DROP INDEX CONCURRENTLY cannot run inside a transaction.
-- Apply this file with psql autocommit before deploying code that allows multiple accepted answers.

CREATE INDEX CONCURRENTLY idx_answers_accepted_question
    ON answers(question_id)
    WHERE is_accepted;

DROP INDEX CONCURRENTLY uidx_accepted_answer;
