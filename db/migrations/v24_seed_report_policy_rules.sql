-- Initial conservative report-review policy set.
-- Automatic suspension is reserved for explicit, high-confidence harm. Ambiguous cases go to hold.
BEGIN;

WITH policy_guard AS (
    SELECT $$Automatic suspension requires direct evidence authored or sent by reported_user and uncertain=false. Never auto-suspend when target, intent, consent, authorship, or context is ambiguous. Never auto-suspend quotations, reports, condemnation, education, prevention, news, fiction, role-play, game talk, consensual adult interaction, reciprocal banter, or mutual argument unless the rule-specific harmful conduct is independently explicit.$$::text AS suspend_guard
),
seed(
    rule_code, title, category, specific_criteria, decision, severity, min_confidence,
    evidence_types, priority, revision, positive_examples, negative_examples
) AS (
    VALUES
    (
        'SAFETY-TARGETED-VIOLENCE-001',
        'Credible targeted violence threat',
        'violence_threat',
        $$Match only when the reported_user directly threatens severe physical harm against an identifiable person or group and credibility is supported by intent plus at least one of a plan, means, time, place, repeated pursuit, or an explicit conditional demand.$$,
        'suspend', 'critical', 0.9800::numeric(5,4), 'text', 1000, 1,
        jsonb_build_array(
            '특정인을 지목해 심각한 위해를 가하겠다고 말하며 시간이나 장소를 제시한다.',
            '요구를 따르지 않으면 찾아가 폭행하겠다고 반복하며 수단을 언급한다.'
        ),
        jsonb_build_array(
            '게임 속 상대를 쓰러뜨리겠다는 표현이다.',
            '뉴스의 위협 발언을 인용하며 비판한다.',
            '대상과 실행 정황이 없는 과장된 관용 표현이다.'
        )
    ),
    (
        'SAFETY-SEXUAL-COERCION-001',
        'Sexual exploitation or coercion by text',
        'sexual_exploitation',
        $$Match only for explicit textual solicitation, coercion, or blackmail in which the reported_user demands sexual conduct or material, offers a transaction to a person explicitly identified as a minor, or threatens consequences for sexual compliance. Do not use this rule merely because sexual media is described; media evidence belongs to the exploitative-media rule.$$,
        'suspend', 'critical', 0.9900::numeric(5,4), 'text', 990, 1,
        jsonb_build_array(
            '미성년자라고 명시된 상대에게 성적 자료를 요구하며 대가를 제안한다.',
            '성적 요구를 거절하면 사적 정보를 공개하겠다고 협박한다.'
        ),
        jsonb_build_array(
            '성인 간 동의와 안전을 전제로 한 성 건강 상담이다.',
            '피해 사실을 신고하거나 성착취 행위를 비판한다.',
            '연령과 동의 여부가 불명확한 성적 표현만 있다.'
        )
    ),
    (
        'SAFETY-EXPLOITATIVE-MEDIA-001',
        'Verified non-consensual exploitative media',
        'sexual_exploitation_media',
        $$Match only when verified image evidence and reported_user text together establish non-consensual distribution, a credible threat to distribute intimate media, or sexual exploitation involving a person explicitly established as a minor by reliable context. Do not infer age, identity, or consent from appearance alone.$$,
        'suspend', 'critical', 0.9900::numeric(5,4), 'both', 980, 1,
        jsonb_build_array(
            '검증된 사적 이미지와 함께 거절하면 공개하겠다는 신고 대상자의 문구가 있다.',
            '신뢰할 수 있는 문맥에서 미성년자임이 명시되고 신고 대상자가 성적 이미지를 거래한다.'
        ),
        jsonb_build_array(
            '이미지만으로 인물의 연령이나 동의를 추정한다.',
            '보도·신고·피해 지원 목적으로 고위험 이미지를 설명한다.',
            '검증 이미지 없이 텍스트가 이미지 유포를 주장하기만 한다.'
        )
    ),
    (
        'PRIVACY-DOXXING-HARM-001',
        'Doxxing with credible harm intent',
        'privacy_doxxing',
        $$Match when the reported_user publishes, requests, or coordinates non-public identifying or location information about a person with explicit stalking, swatting, violence, intimidation, or coordinated-harassment intent.$$,
        'suspend', 'critical', 0.9800::numeric(5,4), 'text', 970, 1,
        jsonb_build_array(
            '상대의 집 주소와 동선을 공개하며 찾아가 공격하라고 선동한다.',
            '비공개 전화번호를 모아 집단 괴롭힘에 쓰자고 요청한다.'
        ),
        jsonb_build_array(
            '공개된 사업장 주소를 약속 장소로 공유한다.',
            '연락처 소유자가 직접 공개한 번호를 동의된 목적으로 전달한다.',
            '정보가 비공개인지 위해 목적이 있는지 불명확하다.'
        )
    ),
    (
        'SAFETY-SELF-HARM-COERCION-001',
        'Targeted self-harm coercion',
        'self_harm_coercion',
        $$Match when the reported_user persistently commands, pressures, celebrates, or gives actionable instructions for an identifiable person to die or seriously self-harm, and the language is directed at that person as conduct to perform.$$,
        'suspend', 'critical', 0.9800::numeric(5,4), 'text', 960, 1,
        jsonb_build_array(
            '특정인에게 자해 방법을 행동 지시로 제시하며 실제 실행을 압박한다.',
            '상대의 죽음을 반복적으로 종용하고 실행 여부를 확인하려 한다.'
        ),
        jsonb_build_array(
            '위기 상황의 상대를 말리고 전문 도움을 안내한다.',
            '회복 경험이나 예방 정보를 공유한다.',
            '허구 작품의 자해 장면을 토론한다.'
        )
    ),
    (
        'HATE-VIOLENCE-INCITEMENT-001',
        'Protected-class violence or rights-removal incitement',
        'hate_violence',
        $$Match only when the reported_user explicitly calls for violence, forced expulsion, or removal of basic rights against people because of a protected characteristic. Derogatory language without those high-risk elements belongs to contextual hold.$$,
        'suspend', 'critical', 0.9800::numeric(5,4), 'text', 950, 1,
        jsonb_build_array(
            '보호 대상 집단을 폭행하거나 제거하자고 직접 선동한다.',
            '특정 정체성 집단의 기본권을 박탈하고 강제로 추방하자고 촉구한다.'
        ),
        jsonb_build_array(
            '혐오 발언을 인용해 문제점을 설명한다.',
            '정책이나 종교적 교리를 비판하지만 사람에 대한 폭력 촉구는 없다.',
            '모욕적 표현은 있으나 폭력·추방·권리 박탈 요소가 없다.'
        )
    ),
    (
        'EXTREMISM-OPERATIONAL-HARM-001',
        'Operational support for terrorist or extremist attack',
        'violent_extremism',
        $$Match only when the reported_user recruits for, coordinates, funds, supplies, or assigns concrete roles for a real violent extremist or terrorist attack. Ideological discussion or propaganda without operational conduct belongs to hold.$$,
        'suspend', 'critical', 0.9900::numeric(5,4), 'text', 940, 1,
        jsonb_build_array(
            '실제 공격 표적과 시간을 정하고 참여자별 역할을 모집한다.',
            '폭력 조직의 공격을 위해 자금과 물자를 보내는 구체적 절차를 조율한다.'
        ),
        jsonb_build_array(
            '극단주의 사건을 보도하거나 역사적으로 연구한다.',
            '폭력 이념을 비판하거나 이탈 지원 정보를 제공한다.',
            '지지 표현처럼 보이나 실제 공격 모집·지원 여부가 불명확하다.'
        )
    ),
    (
        'WEAPONS-ATTACK-ENABLEMENT-001',
        'Weapons or toxic-agent enablement for a real attack',
        'weapons_attack_enablement',
        $$Match only when an identifiable real-world attack intent is combined with actionable manufacturing, procurement, placement, concealment, or evasion instructions for weapons, explosives, or toxic agents.$$,
        'suspend', 'critical', 0.9900::numeric(5,4), 'text', 930, 1,
        jsonb_build_array(
            '특정 장소 공격 계획과 함께 폭발물 제조·배치 절차를 조율한다.',
            '식별 가능한 피해 대상을 언급하며 독극물 조달과 탐지 회피 방법을 제공한다.'
        ),
        jsonb_build_array(
            '합법적인 안전교육이나 역사·과학 설명이다.',
            '공격 의도가 확인되지 않은 일반적 위험물 질문이다.',
            '허구 창작의 설정을 논의한다.'
        )
    ),
    (
        'HARASSMENT-STALKING-COORDINATED-001',
        'Stalking or coordinated targeted harassment',
        'severe_harassment',
        $$Match only when an identifiable person is targeted through stalking, location tracking, recruitment of others, retaliation threats, or persistent unwanted contact after a clear request to stop. Insults or humiliation alone are insufficient.$$,
        'suspend', 'high', 0.9800::numeric(5,4), 'text', 900, 1,
        jsonb_build_array(
            '중단 요구 뒤에도 상대의 위치를 추적하며 여러 사람에게 공격을 요청한다.',
            '연락을 거부한 상대에게 불이익과 보복을 반복적으로 위협한다.'
        ),
        jsonb_build_array(
            '일회성 모욕이나 상호 말다툼이다.',
            '행동이나 주장에 대한 비판이며 추적·동원·보복이 없다.',
            '중단 요구나 반복성 여부가 불명확하다.'
        )
    ),
    (
        'SEXUAL-HARASSMENT-COERCIVE-001',
        'Coercive or retaliatory sexual harassment',
        'sexual_harassment',
        $$Match only when the reported_user repeats unwanted sexual demands after refusal, uses authority or threatened disadvantage to obtain sexual compliance, or threatens exposure or retaliation tied to a sexual demand. Sexual insults alone are insufficient.$$,
        'suspend', 'high', 0.9800::numeric(5,4), 'text', 890, 1,
        jsonb_build_array(
            '거절 의사를 밝힌 상대에게 성적 요구를 반복하며 불이익을 암시한다.',
            '성적 행위에 응하지 않으면 사적 내용을 공개하겠다고 협박한다.'
        ),
        jsonb_build_array(
            '성인 당사자 사이의 상호 동의된 대화다.',
            '일회성 성적 모욕만 있고 강요·보복·반복 정황이 없다.',
            '동의나 거절 여부가 불명확하다.'
        )
    ),
    (
        'FRAUD-PHISHING-EXTORTION-001',
        'High-confidence phishing, theft, or extortion',
        'fraud_extortion',
        $$Match only when the reported_user clearly impersonates a trusted entity to steal credentials, authentication codes, financial data, or funds, or explicitly demands money or property through a credible threat. Ordinary payment coordination is excluded.$$,
        'suspend', 'high', 0.9800::numeric(5,4), 'text', 880, 1,
        jsonb_build_array(
            '기관을 사칭해 비밀번호와 인증번호 입력을 요구하는 링크를 보낸다.',
            '피해를 주거나 사적 정보를 공개하겠다며 송금을 요구한다.'
        ),
        jsonb_build_array(
            '합의된 비용을 공식 결제수단으로 요청한다.',
            '피싱 사례를 경고하며 링크를 클릭하지 말라고 안내한다.',
            '사칭·기망·협박 여부가 불명확한 송금 요청이다.'
        )
    ),
    (
        'IMAGE-SENSITIVE-CONTENT-001',
        'Potentially abusive sensitive image',
        'sensitive_image',
        $$Match when an image appears to contain graphic violence, explicit sexual material, intimate imagery, or another high-risk visual but consent, target, intent, or automatic-suspension eligibility is not reliably established. For this image rule, cite only message IDs that contain verified image evidence; do not cite surrounding text-only messages. Do not infer identity, age, or consent from appearance.$$,
        'hold', 'medium', 0.9000::numeric(5,4), 'image', 800, 1,
        jsonb_build_array(
            '신고 대상이 전송한 검증 이미지에 고위험 시각물이 보이지만 동의 여부가 불명확하다.'
        ),
        jsonb_build_array(
            '일반 풍경, 음식, 문서 또는 위험 요소가 없는 이미지다.',
            '고위험 행위를 비판하는 보도 화면임이 문맥으로 명확하다.'
        )
    ),
    (
        'SAFETY-AMBIGUOUS-THREAT-001',
        'Ambiguous threat requiring review',
        'ambiguous_threat',
        $$Match when language could be a threat or coercive intimidation but target, intent, capability, timing, quotation status, or figurative meaning is unclear. Prefer hold whenever the automatic-suspension credibility elements are incomplete.$$,
        'hold', 'medium', 0.7600::numeric(5,4), 'text', 790, 1,
        jsonb_build_array(
            '상대에게 두고 보자고 반복하지만 구체적 위해 내용은 불명확하다.',
            '조건부 위협처럼 보이지만 농담이나 인용 가능성을 배제하기 어렵다.'
        ),
        jsonb_build_array(
            '대상·의도·실행 단서가 모두 명확한 심각한 위협이다.',
            '명백한 게임 상황의 경쟁 표현이다.'
        )
    ),
    (
        'HARASSMENT-CONTEXTUAL-001',
        'Context-dependent insult or harassment',
        'contextual_harassment',
        $$Match when insults, profanity, ridicule, humiliation, or unwanted contact target a person but repetition, mutuality, a request to stop, power imbalance, or safety impact is insufficiently clear.$$,
        'hold', 'medium', 0.7500::numeric(5,4), 'text', 780, 1,
        jsonb_build_array(
            '모욕적 표현이 특정인에게 향하지만 일회성인지 반복 괴롭힘인지 불명확하다.',
            '상대가 불쾌감을 표시했으나 대화의 상호성이나 반복성이 충분하지 않다.'
        ),
        jsonb_build_array(
            '추적·집단 동원·보복을 동반한 명확한 괴롭힘이다.',
            '행동에 대한 비판만 있고 모욕이나 위협은 없다.'
        )
    ),
    (
        'SEXUAL-HARASSMENT-CONTEXTUAL-001',
        'Context-dependent sexual harassment',
        'contextual_sexual_harassment',
        $$Match when sexual comments, requests, or degradation target a person but consent, refusal, repetition, authority, retaliation, or coercion is unclear. Do not auto-suspend for sexual insult alone.$$,
        'hold', 'medium', 0.8000::numeric(5,4), 'text', 770, 1,
        jsonb_build_array(
            '성적 표현이 특정인에게 향하지만 동의나 거절 여부가 불명확하다.',
            '성적 모욕이 있으나 반복·강요·보복 정황이 충분하지 않다.'
        ),
        jsonb_build_array(
            '거절 뒤 불이익을 암시하며 성적 요구를 반복한다.',
            '상호 동의된 성인 간 대화임이 명확하다.'
        )
    ),
    (
        'HATE-DEROGATORY-CONTEXTUAL-001',
        'Context-dependent protected-class attack',
        'contextual_hate',
        $$Match when derogatory or exclusionary language may attack people based on a protected characteristic but quotation, counterspeech, self-reference, reclaimed language, institutional criticism, or target identity remains unclear.$$,
        'hold', 'medium', 0.8500::numeric(5,4), 'text', 760, 1,
        jsonb_build_array(
            '보호 특성을 겨냥한 모욕처럼 보이지만 인용이나 자기지칭 여부가 불명확하다.'
        ),
        jsonb_build_array(
            '보호 집단에 대한 폭력·추방을 직접 선동한다.',
            '혐오 표현을 명시적으로 비판하거나 교육 목적으로 인용한다.'
        )
    ),
    (
        'PRIVACY-EXPOSURE-UNCLEAR-001',
        'Potential privacy exposure requiring review',
        'privacy_exposure',
        $$Match when personal, contact, identity, or location information is shared but its public status, owner consent, ownership, or harmful purpose is not clear enough for a doxxing decision.$$,
        'hold', 'medium', 0.8500::numeric(5,4), 'text', 750, 1,
        jsonb_build_array(
            '개인 연락처를 공유했지만 본인 공개 정보인지 동의가 있었는지 알 수 없다.'
        ),
        jsonb_build_array(
            '공개 사업장 연락처를 안내한다.',
            '연락처 소유자가 직접 공개한 정보를 동의된 목적으로 공유한다.'
        )
    ),
    (
        'EXTREMISM-RECRUITMENT-CONTEXTUAL-001',
        'Potential extremist recruitment or propaganda',
        'contextual_extremism',
        $$Match when content may praise, recruit for, or materially support violent extremist activity but reporting, research, condemnation, disengagement support, parody, or actual operational intent remains unclear.$$,
        'hold', 'high', 0.9000::numeric(5,4), 'text', 740, 1,
        jsonb_build_array(
            '폭력 조직 참여를 권하는 듯하지만 보도·연구·풍자 맥락을 배제하기 어렵다.'
        ),
        jsonb_build_array(
            '실제 공격의 표적·시간·역할·자금 지원을 구체적으로 조율한다.',
            '극단주의를 비판하거나 이탈 지원 정보를 제공한다.'
        )
    ),
    (
        'WEAPONS-DANGEROUS-INSTRUCTION-001',
        'Potentially dangerous weapons or toxic-agent instruction',
        'dangerous_instruction',
        $$Match when actionable weapons, explosives, or toxic-agent instructions are present but real attack intent, lawful professional use, safety education, historical context, or fictional purpose cannot be resolved.$$,
        'hold', 'high', 0.9000::numeric(5,4), 'text', 730, 1,
        jsonb_build_array(
            '위험물 제조 절차가 구체적이지만 실제 공격 목적은 확인되지 않는다.'
        ),
        jsonb_build_array(
            '실제 공격 대상과 결합된 제조·조달·배치 지시다.',
            '위험 요소를 제거하기 위한 안전교육이다.'
        )
    ),
    (
        'FRAUD-SUSPICIOUS-SOLICITATION-001',
        'Suspicious financial or credential solicitation',
        'suspected_fraud',
        $$Match when credentials, authentication codes, financial data, money, or off-platform payment are requested with suspicious urgency or identity claims, but deception, impersonation, theft, or coercion is not sufficiently established.$$,
        'hold', 'medium', 0.8500::numeric(5,4), 'text', 720, 1,
        jsonb_build_array(
            '긴급 송금이나 인증번호를 요구하지만 사칭 여부를 확인하기 어렵다.'
        ),
        jsonb_build_array(
            '기관을 사칭해 자격증명을 탈취하는 정황이 명확하다.',
            '합의된 비용을 검증된 공식 결제수단으로 요청한다.'
        )
    ),
    (
        'SELF-HARM-DANGEROUS-ENCOURAGEMENT-001',
        'Potentially dangerous self-harm encouragement',
        'self_harm_risk',
        $$Match when content may encourage or normalize serious self-harm but a specific target, actionable direction, malicious pressure, quotation, prevention, recovery, or support context is unresolved.$$,
        'hold', 'high', 0.9000::numeric(5,4), 'text', 710, 1,
        jsonb_build_array(
            '자해를 부추기는 듯하지만 특정 대상과 실제 행동 지시 여부가 불명확하다.'
        ),
        jsonb_build_array(
            '특정인에게 실행 방법을 주며 실제 행동을 압박한다.',
            '전문 도움과 위기 지원을 안내한다.'
        )
    ),
    (
        'EXPLOITATION-SUSPECTED-001',
        'Potential exploitative media requiring review',
        'suspected_exploitation',
        $$Match when verified image evidence and related reported_user text raise a credible exploitation or non-consensual-distribution concern, but age, identity, consent, authorship, or distribution intent is not reliably established.$$,
        'hold', 'high', 0.9000::numeric(5,4), 'both', 700, 1,
        jsonb_build_array(
            '검증 이미지와 유포 암시 문구가 함께 있으나 동의 여부를 확인할 수 없다.'
        ),
        jsonb_build_array(
            '비동의 유포 협박과 검증 이미지가 모두 명확하다.',
            '연령·신원·동의를 외형만으로 추정한다.'
        )
    ),
    (
        'SPAM-REPEATED-SOLICITATION-001',
        'Repeated unsolicited promotion or solicitation',
        'spam',
        $$Match when the reported_user repeatedly sends substantially similar unsolicited advertisements, referral solicitations, off-platform contact requests, or disruptive promotional links, especially after refusal.$$,
        'hold', 'medium', 0.8500::numeric(5,4), 'text', 690, 1,
        jsonb_build_array(
            '대화와 무관한 홍보 문구와 링크를 여러 차례 반복 전송한다.',
            '거절 이후에도 가입 추천이나 외부 연락 유도를 계속한다.'
        ),
        jsonb_build_array(
            '상대가 요청한 상품 정보를 한 번 제공한다.',
            '대화 주제와 직접 관련된 일반 링크를 공유한다.'
        )
    ),
    (
        'NORMAL-DISAGREEMENT-001',
        'Ordinary disagreement or criticism',
        'benign_disagreement',
        $$Match when the reported content is disagreement, correction, criticism, refusal, or negative feedback about ideas or conduct without a credible threat, protected-class attack, sustained targeting, exploitation, or private-data disclosure.$$,
        'normal', 'low', 0.8500::numeric(5,4), 'text', 200, 1,
        jsonb_build_array(
            '주장에 동의하지 않는 이유를 설명하거나 행동을 비판한다.',
            '요청을 거절하거나 대화를 끝내겠다고 명확히 말한다.'
        ),
        jsonb_build_array(
            '특정인을 반복적으로 위협하거나 추적한다.',
            '보호 대상 집단 전체에 대한 폭력을 촉구한다.'
        )
    ),
    (
        'NORMAL-BENIGN-LINK-001',
        'Benign relevant link or contact sharing',
        'benign_link',
        $$Match when a link or contact detail is relevant, not deceptively presented, not repeatedly unsolicited, and is either official public information, a public business contact, or information voluntarily published by its owner for the shared purpose.$$,
        'normal', 'low', 0.9000::numeric(5,4), 'text', 190, 1,
        jsonb_build_array(
            '질문에 답하기 위해 공식 문서 링크를 한 번 공유한다.',
            '공개 사업장 연락처나 소유자가 직접 공개한 연락처를 동의된 목적으로 전달한다.'
        ),
        jsonb_build_array(
            '기관을 사칭한 링크에서 비밀번호나 인증번호를 요구한다.',
            '거절 후에도 동일한 홍보 링크를 반복한다.'
        )
    ),
    (
        'NORMAL-CONSENSUAL-BANTER-001',
        'Clearly consensual banter',
        'consensual_banter',
        $$Match only when the surrounding conversation clearly shows reciprocal, comparable, and welcome joking or teasing, with no threat, coercion, protected-class attack, sexual pressure, or request to stop.$$,
        'normal', 'low', 0.8800::numeric(5,4), 'text', 180, 1,
        jsonb_build_array(
            '참여자들이 비슷한 표현으로 웃으며 주고받고 대화를 자발적으로 이어간다.'
        ),
        jsonb_build_array(
            '한쪽이 중단을 요구했는데도 모욕을 계속한다.',
            '위협이나 성적 강요가 농담이라는 이름으로 반복된다.'
        )
    ),
    (
        'NORMAL-SAFETY-REPORTING-001',
        'Safety reporting, counterspeech, or education',
        'safety_reporting',
        $$Match when harmful language or conduct is quoted or described to report it, condemn it, educate about it, prevent harm, document news or research, or support a victim, without endorsing or facilitating the harmful conduct.$$,
        'normal', 'low', 0.9000::numeric(5,4), 'text', 170, 1,
        jsonb_build_array(
            '위협 메시지를 신고하기 위해 인용하고 도움을 요청한다.',
            '혐오 표현의 문제를 설명하며 반대한다.'
        ),
        jsonb_build_array(
            '인용 형식을 빌려 실제 폭력을 선동한다.',
            '피해 지원이 아니라 공격 실행 절차를 제공한다.'
        )
    ),
    (
        'NORMAL-CONSENSUAL-ADULT-DISCUSSION-001',
        'Consensual adult relationship or health discussion',
        'consensual_adult_discussion',
        $$Match when adults clearly engage in consensual relationship, sexual-health, medical, or educational discussion with no coercion, exploitation, unwanted targeting, threat, or non-consensual media distribution.$$,
        'normal', 'low', 0.9000::numeric(5,4), 'text', 160, 1,
        jsonb_build_array(
            '성인 당사자들이 동의와 안전을 전제로 관계나 성 건강을 상담한다.'
        ),
        jsonb_build_array(
            '거절한 상대에게 성적 행위를 반복 강요한다.',
            '사적 이미지를 공개하겠다고 협박한다.'
        )
    )
),
normalized AS (
    SELECT
        seed.rule_code,
        seed.title,
        seed.category,
        CASE
            WHEN seed.decision = 'suspend'
                THEN policy_guard.suspend_guard || E'\n\nRule-specific criteria: ' || seed.specific_criteria
            ELSE seed.specific_criteria
        END AS criteria,
        seed.decision,
        seed.severity,
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
               rule_code, title, category, criteria, decision, severity, min_confidence::text,
               evidence_types, priority::text, revision::text,
               positive_examples::text, negative_examples::text
           ), 'UTF8'), 'sha256'), 'hex') AS content_hash
    FROM normalized
)
INSERT INTO ai_report_policy_rules (
    rule_code, title, category, criteria, decision, severity, min_confidence,
    evidence_types, priority, positive_examples, negative_examples,
    active, revision, content_hash
)
SELECT
    rule_code, title, category, criteria, decision, severity, min_confidence,
    evidence_types, priority, positive_examples, negative_examples,
    TRUE, revision, content_hash
FROM hashed
ON CONFLICT (rule_code) DO NOTHING;

COMMIT;
