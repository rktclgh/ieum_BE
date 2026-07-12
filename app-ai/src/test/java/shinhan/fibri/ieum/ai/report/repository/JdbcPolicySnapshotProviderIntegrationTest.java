package shinhan.fibri.ieum.ai.report.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class JdbcPolicySnapshotProviderIntegrationTest {

	private static final String DATABASE = "ieum_ai_policy_snapshot";

	private JdbcClient jdbc;
	private JdbcPolicySnapshotProvider provider;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"))
			.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)")
			.update();
	}

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		provider = new JdbcPolicySnapshotProvider(jdbc);
	}

	@Test
	void loadsOnlyActiveRulesInDeterministicPriorityOrder() {
		insertRule("CONTENT-LOW", "normal", "low", 10, true, "1");
		insertRule("CONTENT-HIGH", "suspend", "high", 20, true, "2");
		insertRule("CONTENT-INACTIVE", "hold", "medium", 999, false, "3");

		ReportPolicySnapshot snapshot = provider.loadActiveSnapshot();

		assertThat(snapshot.rules()).extracting(rule -> rule.ruleCode())
			.containsExactly("CONTENT-HIGH", "CONTENT-LOW");
		assertThat(snapshot.policySetHash()).matches("[0-9a-f]{64}");
	}

	@Test
	void loadsModelFacingPolicyCriteriaAndExamples() {
		insertRule("CONTENT-SPAM", "hold", "medium", 10, true, "1");
		jdbc.sql("""
			UPDATE ai_report_policy_rules
			SET title = 'Spam policy',
			    criteria = 'Repeated unsolicited promotional messages',
			    positive_examples = '["Buy now: example.com"]'::jsonb,
			    negative_examples = '["A single relevant recommendation"]'::jsonb
			WHERE rule_code = 'CONTENT-SPAM'
			""").update();

		ReportPolicySnapshot snapshot = provider.loadActiveSnapshot();

		assertThat(snapshot.rules()).singleElement().satisfies(rule -> {
			assertThat(rule.title()).isEqualTo("Spam policy");
			assertThat(rule.criteria()).isEqualTo("Repeated unsolicited promotional messages");
			assertThat(rule.positiveExamples()).containsExactly("Buy now: example.com");
			assertThat(rule.negativeExamples()).containsExactly("A single relevant recommendation");
		});
	}

	@Test
	void producesTheSameHashForTheSameActiveRulesRegardlessOfInsertOrder() {
		insertRule("CONTENT-B", "hold", "medium", 10, true, "1");
		insertRule("CONTENT-A", "suspend", "high", 20, true, "2");
		String firstHash = provider.loadActiveSnapshot().policySetHash();

		jdbc.sql("DELETE FROM ai_report_policy_rules").update();
		insertRule("CONTENT-A", "suspend", "high", 20, true, "2");
		insertRule("CONTENT-B", "hold", "medium", 10, true, "1");
		String secondHash = provider.loadActiveSnapshot().policySetHash();

		assertThat(secondHash).isEqualTo(firstHash);
	}

	@Test
	void returnsAnEmptySnapshotWhenNoActivePolicyExists() {
		insertRule("CONTENT-INACTIVE", "hold", "medium", 10, false, "1");

		ReportPolicySnapshot snapshot = provider.loadActiveSnapshot();

		assertThat(snapshot.rules()).isEmpty();
		assertThat(snapshot.policySetHash()).matches("[0-9a-f]{64}");
	}

	@Test
	void changesTheHashWhenAnActiveRuleRevisionAndContentChange() {
		insertRule("CONTENT-HIGH", "suspend", "high", 20, true, "1");
		String firstHash = provider.loadActiveSnapshot().policySetHash();

		jdbc.sql("""
			UPDATE ai_report_policy_rules
			SET revision = 2,
			    content_hash = repeat('2', 64)
			WHERE rule_code = 'CONTENT-HIGH'
			""").update();
		String secondHash = provider.loadActiveSnapshot().policySetHash();

		assertThat(secondHash).isNotEqualTo(firstHash);
	}

	private void insertRule(
		String ruleCode,
		String decision,
		String severity,
		int priority,
		boolean active,
		String contentHashDigit
	) {
		jdbc.sql("""
			INSERT INTO ai_report_policy_rules (
				rule_code, title, category, criteria, decision, severity, min_confidence,
				evidence_types, priority, active, content_hash
			)
			VALUES (
				:ruleCode, :ruleCode, :category, 'criteria', :decision, :severity, 0.8000,
				'text', :priority, :active, repeat(:contentHashDigit, 64)
			)
			""")
			.param("ruleCode", ruleCode)
			.param("category", ruleCode.toLowerCase())
			.param("decision", decision)
			.param("severity", severity)
			.param("priority", priority)
			.param("active", active)
			.param("contentHashDigit", contentHashDigit)
			.update();
	}
}
