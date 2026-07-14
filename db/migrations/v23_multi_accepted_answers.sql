BEGIN;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM answers a
        JOIN questions q ON q.question_id = a.question_id
        WHERE a.is_accepted AND NOT q.is_resolved
    ) THEN
        RAISE EXCEPTION 'accepted answer exists on unresolved question';
    END IF;

    IF EXISTS (
        SELECT 1
        FROM questions q
        WHERE q.is_resolved
          AND NOT EXISTS (
              SELECT 1
              FROM answers a
              WHERE a.question_id = q.question_id
                AND a.is_accepted
          )
    ) THEN
        RAISE EXCEPTION 'resolved question has no accepted answer';
    END IF;
END;
$$;

DROP INDEX IF EXISTS uidx_accepted_answer;
CREATE INDEX idx_answers_accepted_question
    ON answers(question_id)
    WHERE is_accepted;

WITH actual AS (
    SELECT u.user_id,
           count(a.answer_id)::integer AS accepted_count
    FROM users u
    LEFT JOIN answers a
      ON a.author_id = u.user_id
     AND a.is_accepted
     AND NOT a.is_ai
    GROUP BY u.user_id
)
UPDATE users u
SET accepted_count = actual.accepted_count,
    grade = CASE
        WHEN actual.accepted_count >= 50 THEN 'diamond'::user_grade
        WHEN actual.accepted_count >= 30 THEN 'platinum'::user_grade
        WHEN actual.accepted_count >= 15 THEN 'gold'::user_grade
        WHEN actual.accepted_count >= 5 THEN 'silver'::user_grade
        ELSE 'bronze'::user_grade
    END
FROM actual
WHERE actual.user_id = u.user_id
  AND (u.accepted_count, u.grade) IS DISTINCT FROM (
      actual.accepted_count,
      CASE
          WHEN actual.accepted_count >= 50 THEN 'diamond'::user_grade
          WHEN actual.accepted_count >= 30 THEN 'platinum'::user_grade
          WHEN actual.accepted_count >= 15 THEN 'gold'::user_grade
          WHEN actual.accepted_count >= 5 THEN 'silver'::user_grade
          ELSE 'bronze'::user_grade
      END
  );

COMMIT;
