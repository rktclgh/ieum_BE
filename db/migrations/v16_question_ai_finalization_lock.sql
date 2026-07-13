-- One-shot v15 -> v16 upgrade. AI finalization locks the question and its pin
-- through SECURITY DEFINER functions without granting domain-table DML.
BEGIN;

CREATE OR REPLACE FUNCTION public.ai_lock_active_question(p_question_id BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
BEGIN
    PERFORM 1
      FROM public.questions q
      JOIN public.pins p ON p.pin_id = q.pin_id
     WHERE q.question_id = p_question_id
       AND q.deleted_at IS NULL
       AND p.deleted_at IS NULL
       FOR SHARE OF q, p;

    RETURN FOUND;
END;
$$;

REVOKE ALL ON FUNCTION public.ai_lock_active_question(BIGINT) FROM PUBLIC;

CREATE OR REPLACE FUNCTION public.insert_ai_answer_if_active(p_question_id BIGINT, p_content TEXT)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_answer_id BIGINT;
BEGIN
    IF p_content IS NULL OR btrim(p_content) = '' THEN
        RAISE EXCEPTION 'AI answer content must not be blank' USING ERRCODE = '22023';
    END IF;

    PERFORM 1
      FROM public.questions q
      JOIN public.pins p ON p.pin_id = q.pin_id
     WHERE q.question_id = p_question_id
       AND q.deleted_at IS NULL
       AND p.deleted_at IS NULL
       FOR SHARE OF q, p;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Active question not found' USING ERRCODE = 'P0002';
    END IF;

    SELECT answer_id
      INTO v_answer_id
      FROM public.answers
     WHERE question_id = p_question_id
       AND is_ai;
    IF v_answer_id IS NOT NULL THEN
        RETURN v_answer_id;
    END IF;

    INSERT INTO public.answers(question_id, author_id, is_ai, content)
    VALUES (p_question_id, NULL, TRUE, p_content)
    RETURNING answer_id INTO v_answer_id;
    RETURN v_answer_id;
EXCEPTION
    WHEN unique_violation THEN
        SELECT answer_id
          INTO v_answer_id
          FROM public.answers
         WHERE question_id = p_question_id
           AND is_ai;
        RETURN v_answer_id;
END;
$$;

REVOKE ALL ON FUNCTION public.insert_ai_answer_if_active(BIGINT, TEXT) FROM PUBLIC;

COMMIT;
