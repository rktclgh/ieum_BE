-- One-shot v21 -> v22 upgrade. Lock accepted-answer knowledge eligibility in
-- app-main's question -> pin -> answer order.
BEGIN;

CREATE FUNCTION public.ai_lock_eligible_accepted_answer(p_answer_id BIGINT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $$
DECLARE
    v_question_id BIGINT;
    v_pin_id BIGINT;
BEGIN
    SELECT q.question_id, q.pin_id
      INTO v_question_id, v_pin_id
      FROM public.questions q
     WHERE q.question_id = (
               SELECT candidate.question_id
                 FROM public.answers candidate
                WHERE candidate.answer_id = p_answer_id
           )
       AND q.deleted_at IS NULL
       FOR SHARE OF q;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    PERFORM 1
      FROM public.pins p
     WHERE p.pin_id = v_pin_id
       AND p.deleted_at IS NULL
       AND p.pin_type = 'question'::public.pin_type
       FOR SHARE OF p;
    IF NOT FOUND THEN
        RETURN FALSE;
    END IF;

    PERFORM 1
      FROM public.answers a
     WHERE a.answer_id = p_answer_id
       AND a.question_id = v_question_id
       AND a.is_accepted
       AND NOT a.is_ai
       AND a.author_id IS NOT NULL
       FOR SHARE OF a;

    RETURN FOUND;
END;
$$;

REVOKE ALL ON FUNCTION public.ai_lock_eligible_accepted_answer(BIGINT) FROM PUBLIC;

COMMIT;
