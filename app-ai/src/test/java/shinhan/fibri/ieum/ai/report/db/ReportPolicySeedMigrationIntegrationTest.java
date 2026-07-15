package shinhan.fibri.ieum.ai.report.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class ReportPolicySeedMigrationIntegrationTest {

	private static final String DATABASE = "ieum_ai_report_policy_seed";
	private JdbcClient jdbc;

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
	}

	@Test
	void v24SeedsAConservativeVersionedModerationPolicySet() {
		SqlScriptRunner.run(DATABASE, "migrations/v24_seed_report_policy_rules.sql");

		List<Map<String, Object>> rules = jdbc.sql("""
			SELECT rule_code, criteria, decision, severity, min_confidence, evidence_types,
			       priority, revision, active, jsonb_array_length(positive_examples) AS positives,
			       jsonb_array_length(negative_examples) AS negatives
			FROM ai_report_policy_rules
			ORDER BY priority DESC, rule_code
			""").query().listOfRows();

		assertThat(rules).hasSize(28);
		assertThat(rules).allSatisfy(rule -> {
			assertThat(rule.get("active")).isEqualTo(true);
			assertThat(((Number) rule.get("revision")).intValue()).isEqualTo(1);
			assertThat(((Number) rule.get("positives")).intValue()).isPositive();
			assertThat(((Number) rule.get("negatives")).intValue()).isPositive();
		});
		assertThat(rules.stream().filter(rule -> "suspend".equals(rule.get("decision"))).toList())
			.hasSize(11)
			.allSatisfy(rule -> {
				assertThat(rule.get("severity")).isIn("high", "critical");
				assertThat(((java.math.BigDecimal) rule.get("min_confidence")))
					.isGreaterThanOrEqualTo(new java.math.BigDecimal("0.9700"));
				assertThat((String) rule.get("criteria"))
					.contains("Never auto-suspend when")
					.contains("target, intent, consent, authorship, or context is ambiguous");
			});
		assertThat(rules.stream().filter(rule -> "hold".equals(rule.get("decision"))).toList()).hasSize(12);
		assertThat(rules.stream().filter(rule -> "normal".equals(rule.get("decision"))).toList()).hasSize(5);
		assertThat(rules).extracting(rule -> rule.get("evidence_types"))
			.contains("text", "image", "both");
		assertThat(rules).extracting(rule -> rule.get("rule_code"))
			.contains(
				"SAFETY-SEXUAL-COERCION-001",
				"SAFETY-EXPLOITATIVE-MEDIA-001",
				"EXTREMISM-OPERATIONAL-HARM-001",
				"WEAPONS-ATTACK-ENABLEMENT-001",
				"FRAUD-PHISHING-EXTORTION-001",
				"EXTREMISM-RECRUITMENT-CONTEXTUAL-001",
				"WEAPONS-DANGEROUS-INSTRUCTION-001",
				"NORMAL-SAFETY-REPORTING-001"
			)
			.doesNotContain("HARASSMENT-SEVERE-001", "SEXUAL-HARASSMENT-SEVERE-001");
	}

	@Test
	void v24SeparatesTextCoercionFromVerifiedExploitativeMediaEvidence() {
		SqlScriptRunner.run(DATABASE, "migrations/v24_seed_report_policy_rules.sql");

		Map<String, Object> textRule = rule("SAFETY-SEXUAL-COERCION-001");
		Map<String, Object> mediaRule = rule("SAFETY-EXPLOITATIVE-MEDIA-001");
		Map<String, Object> imageHold = rule("IMAGE-SENSITIVE-CONTENT-001");

		assertThat(textRule.get("evidence_types")).isEqualTo("text");
		assertThat((String) textRule.get("criteria")).contains("textual solicitation, coercion, or blackmail");
		assertThat(mediaRule.get("evidence_types")).isEqualTo("both");
		assertThat((String) mediaRule.get("criteria"))
			.contains("verified image evidence")
			.contains("Do not infer age, identity, or consent from appearance alone");
		assertThat(imageHold.get("evidence_types")).isEqualTo("image");
		assertThat((String) imageHold.get("criteria"))
			.contains("cite only message IDs that contain verified image evidence");
	}

	@Test
	void v24StoresAContentHashThatMatchesEveryRuleBody() {
		SqlScriptRunner.run(DATABASE, "migrations/v24_seed_report_policy_rules.sql");

		long mismatches = jdbc.sql("""
			SELECT count(*)
			FROM ai_report_policy_rules
			WHERE content_hash <> encode(digest(convert_to(concat_ws(E'\\n',
			    rule_code, title, category, criteria, decision, severity, min_confidence::text,
			    evidence_types, priority::text, revision::text,
			    positive_examples::text, negative_examples::text
			), 'UTF8'), 'sha256'), 'hex')
			""").query(Long.class).single();

		assertThat(mismatches).isZero();
	}

	@Test
	void v24DoesNotOverwriteAnExistingRuleOrDuplicateRows() {
		jdbc.sql("""
			INSERT INTO ai_report_policy_rules (
			    rule_code, title, category, criteria, decision, severity, min_confidence,
			    evidence_types, priority, positive_examples, negative_examples, revision, content_hash
			)
			VALUES (
			    'SAFETY-TARGETED-VIOLENCE-001', 'operator override', 'custom', 'custom criteria',
			    'hold', 'medium', 0.5000, 'text', 1, '["custom positive"]', '["custom negative"]', 9, :hash
			)
			""").param("hash", "f".repeat(64)).update();

		SqlScriptRunner.run(
			DATABASE,
			"migrations/v24_seed_report_policy_rules.sql",
			"migrations/v24_seed_report_policy_rules.sql"
		);

		assertThat(jdbc.sql("SELECT count(*) FROM ai_report_policy_rules").query(Long.class).single()).isEqualTo(28);
		Map<String, Object> override = jdbc.sql("""
			SELECT title, revision, content_hash FROM ai_report_policy_rules
			WHERE rule_code='SAFETY-TARGETED-VIOLENCE-001'
			""").query().singleRow();
		assertThat(override.get("title")).isEqualTo("operator override");
		assertThat(((Number) override.get("revision")).intValue()).isEqualTo(9);
		assertThat(override.get("content_hash")).isEqualTo("f".repeat(64));
	}

	@Test
	void v27AddsSevenAndThirtyDayRulesWithoutChangingExistingRules() {
		SqlScriptRunner.run(
			DATABASE,
			"migrations/v24_seed_report_policy_rules.sql",
			"migrations/v27_report_policy_sanction_durations.sql"
		);

		assertThat(jdbc.sql("SELECT count(*) FROM ai_report_policy_rules").query(Long.class).single()).isEqualTo(30L);
		assertThat(((Number) ruleWithDuration("HARASSMENT-TARGETED-PROFANITY-001").get("automatic_sanction_days")).intValue())
			.isEqualTo(7);
		assertThat(((Number) ruleWithDuration("SEXUAL-HARASSMENT-TARGETED-001").get("automatic_sanction_days")).intValue())
			.isEqualTo(30);
		assertThat(((Number) ruleWithDuration("SEXUAL-HARASSMENT-TARGETED-001").get("priority")).intValue())
			.isEqualTo(930);
		assertThat(ruleWithDuration("SAFETY-TARGETED-VIOLENCE-001").get("automatic_sanction_days")).isNull();
	}

	private Map<String, Object> rule(String ruleCode) {
		return jdbc.sql("""
			SELECT rule_code, criteria, decision, severity, min_confidence, evidence_types
			FROM ai_report_policy_rules
			WHERE rule_code = :ruleCode
			""").param("ruleCode", ruleCode).query().singleRow();
	}

	private Map<String, Object> ruleWithDuration(String ruleCode) {
		return jdbc.sql("""
			SELECT rule_code, priority, automatic_sanction_days
			FROM ai_report_policy_rules
			WHERE rule_code = :ruleCode
			""").param("ruleCode", ruleCode).query().singleRow();
	}
}
