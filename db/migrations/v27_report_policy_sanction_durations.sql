-- Adds an optional policy-owned automatic suspension duration.
-- Existing policy rows are intentionally preserved with NULL and continue using app-main's legacy fallback.
BEGIN;

ALTER TABLE ai_report_policy_rules
    ADD COLUMN IF NOT EXISTS automatic_sanction_days SMALLINT;

DO $migration$
DECLARE
    duration_type regtype;
BEGIN
    SELECT attribute.atttypid::regtype
    INTO duration_type
    FROM pg_attribute attribute
    WHERE attribute.attrelid = 'public.ai_report_policy_rules'::regclass
      AND attribute.attname = 'automatic_sanction_days'
      AND attribute.attnum > 0
      AND NOT attribute.attisdropped;

    IF duration_type IS DISTINCT FROM 'smallint'::regtype THEN
        RAISE EXCEPTION 'ai_report_policy_rules.automatic_sanction_days must be smallint';
    END IF;

    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint constraint_row
        WHERE constraint_row.conrelid = 'public.ai_report_policy_rules'::regclass
          AND constraint_row.conname = 'ck_ai_report_policy_rules_automatic_sanction_days'
    ) THEN
        ALTER TABLE ai_report_policy_rules
            ADD CONSTRAINT ck_ai_report_policy_rules_automatic_sanction_days
            CHECK (automatic_sanction_days IS NULL OR (
                decision = 'suspend' AND automatic_sanction_days BETWEEN 1 AND 365
            ));
    END IF;
END
$migration$;

WITH policy_guard AS (
    SELECT $$Automatic suspension requires direct evidence authored or sent by reported_user and uncertain=false. Never auto-suspend when target, intent, consent, authorship, or context is ambiguous. Never auto-suspend quotations, reports, condemnation, education, prevention, news, fiction, role-play, game talk, consensual adult interaction, reciprocal banter, or mutual argument unless the rule-specific harmful conduct is independently explicit.$$::text AS suspend_guard
),
seed(
    rule_code, title, category, specific_criteria, decision, severity, automatic_sanction_days,
    min_confidence, evidence_types, priority, revision, positive_examples, negative_examples
) AS (
    VALUES
    (
        'HARASSMENT-TARGETED-PROFANITY-001',
        'Targeted severe profanity or humiliating abuse',
        'severe_harassment',
        $$Match only when the reported_user directly directs severe profanity, dehumanizing slurs, or sustained humiliating abuse at an identifiable person. The direct target and abusive intent must be clear from the cited text. Do not match criticism of conduct, a single ambiguous expletive, self-reference, quoted speech, reporting, condemnation, education, fiction, role-play, game talk, reciprocal banter, or mutual argument.$$,
        'suspend', 'high', 7::smallint, 0.9900::numeric(5,4), 'text', 920, 1,
        jsonb_build_array(
            '상대의 실명이나 식별 가능한 호칭을 붙여 반복적으로 심각한 욕설과 비인격적 모욕을 직접 보낸다.',
            '중단 요구 뒤에도 특정인을 겨냥한 심각한 모욕과 욕설을 지속한다.'
        ),
        jsonb_build_array(
            '상호적으로 주고받는 농담이나 게임 중 과장된 표현이다.',
            '욕설을 인용해 신고하거나 비판하는 맥락이다.',
            '행동이나 주장에 대한 비판일 뿐 특정인에 대한 심각한 모욕이 아니다.'
        )
    ),
    (
        'SEXUAL-HARASSMENT-TARGETED-001',
        'Targeted coercive or retaliatory sexual harassment',
        'sexual_harassment',
        $$Match only when the reported_user continues unwanted sexual demands after an unambiguous refusal, uses authority, threatened disadvantage, exposure, or retaliation to obtain sexual compliance, or directly humiliates an identifiable person through coercive sexual harassment. This stricter rule supersedes the legacy coercive-sexual-harassment rule when both are supported. Do not match consensual adult discussion, sexual-health education, reporting, condemnation, a single ambiguous sexual remark, or context where consent, refusal, target, or coercion is unclear.$$,
        'suspend', 'high', 30::smallint, 0.9900::numeric(5,4), 'text', 930, 1,
        jsonb_build_array(
            '명확한 거절 뒤에도 특정인에게 성적 요구를 반복하고 응하지 않으면 불이익을 주겠다고 말한다.',
            '특정인의 성적 이미지를 공개하겠다는 위협으로 성적 행위를 강요한다.'
        ),
        jsonb_build_array(
            '성인 당사자 사이에서 상호 동의된 성적 대화다.',
            '성희롱 피해를 신고하거나 예방 교육을 위해 행위를 설명한다.',
            '성적 표현은 있으나 대상, 거절, 강요 또는 보복 정황이 불명확하다.'
        )
    )
),
normalized AS (
    SELECT
        seed.rule_code,
        seed.title,
        seed.category,
        policy_guard.suspend_guard || E'\n\nRule-specific criteria: ' || seed.specific_criteria AS criteria,
        seed.decision,
        seed.severity,
        seed.automatic_sanction_days,
        seed.min_confidence,
        seed.evidence_types,
        seed.priority,
        seed.revision,
        seed.positive_examples,
        seed.negative_examples
    FROM seed
    CROSS JOIN policy_guard
),
hashed AS (
    SELECT normalized.*,
           encode(digest(convert_to(concat_ws(E'\n',
               rule_code, title, category, criteria, decision, severity, automatic_sanction_days::text,
               min_confidence::text, evidence_types, priority::text, revision::text,
               positive_examples::text, negative_examples::text
           ), 'UTF8'), 'sha256'), 'hex') AS content_hash
    FROM normalized
)
INSERT INTO ai_report_policy_rules (
    rule_code, title, category, criteria, decision, severity, automatic_sanction_days,
    min_confidence, evidence_types, priority, positive_examples, negative_examples,
    active, revision, content_hash
)
SELECT
    rule_code, title, category, criteria, decision, severity, automatic_sanction_days,
    min_confidence, evidence_types, priority, positive_examples, negative_examples,
    TRUE, revision, content_hash
FROM hashed
ON CONFLICT (rule_code) DO NOTHING;

COMMIT;
