package shinhan.fibri.ieum.main.report.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class ReportCanonicalSchemaIntegrationTest {

	private static final String DATABASE = "ieum_main_report_schema";

	@Test
	void canonicalSchemaExposesReportTablesForMigrationSmokeTests() {
		URL schema = getClass().getClassLoader().getResource("canonical-db/schema.sql");
		assertThat(schema).isNotNull();

		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");

		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		assertThat(columns(jdbc, "reports")).contains(
			"report_id",
			"reporter_id",
			"target_type",
			"message_id",
			"answer_id",
			"reported_user_id",
			"reason",
			"context_snapshot",
			"context_hash",
			"ai_recommendation",
			"ai_policy_version",
			"ai_review_state",
			"ai_review_attempt_id",
			"ai_attempts",
			"ai_next_attempt_at",
			"ai_lease_until",
			"ai_locked_by",
			"ai_last_error_code",
			"ai_last_error_message",
			"ai_decision",
			"ai_policy_set_hash",
			"ai_review_result",
			"status");
		assertThat(enumLabels(jdbc, "report_target_type")).containsExactly("message", "answer");
		assertThat(nullableColumns(jdbc, "reports")).contains("message_id", "answer_id", "reported_user_id");
		assertThat(validCheckConstraints(jdbc, "reports")).contains(
			"ck_reports_target_xor",
			"ck_reports_message_reported_user",
			"ck_reports_answer_manual_only"
		);
		assertThat(indexNames(jdbc, "reports")).contains("idx_reports_answer");
		assertThat(tableExists(jdbc, "user_sanctions")).isTrue();
	}

	private static java.util.List<String> enumLabels(JdbcClient jdbc, String typeName) {
		return jdbc.sql("""
			SELECT enumlabel
			FROM pg_enum
			WHERE enumtypid = (:typeName)::regtype
			ORDER BY enumsortorder
			""")
			.param("typeName", typeName)
			.query(String.class)
			.list();
	}

	private static java.util.List<String> nullableColumns(JdbcClient jdbc, String tableName) {
		return jdbc.sql("""
			SELECT column_name
			FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = :tableName AND is_nullable = 'YES'
			ORDER BY ordinal_position
			""")
			.param("tableName", tableName)
			.query(String.class)
			.list();
	}

	private static java.util.List<String> validCheckConstraints(JdbcClient jdbc, String tableName) {
		return jdbc.sql("""
			SELECT conname
			FROM pg_constraint
			WHERE conrelid = (:tableName)::regclass AND contype = 'c' AND convalidated
			ORDER BY conname
			""")
			.param("tableName", tableName)
			.query(String.class)
			.list();
	}

	private static java.util.List<String> indexNames(JdbcClient jdbc, String tableName) {
		return jdbc.sql("""
			SELECT indexname
			FROM pg_indexes
			WHERE schemaname = 'public' AND tablename = :tableName
			ORDER BY indexname
			""")
			.param("tableName", tableName)
			.query(String.class)
			.list();
	}

	private static java.util.List<String> columns(JdbcClient jdbc, String tableName) {
		return jdbc.sql("""
			SELECT column_name
			FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = :tableName
			ORDER BY ordinal_position
			""")
			.param("tableName", tableName)
			.query(String.class)
			.list();
	}

	private static boolean tableExists(JdbcClient jdbc, String tableName) {
		return jdbc.sql("SELECT to_regclass(:tableName) IS NOT NULL")
			.param("tableName", tableName)
			.query(Boolean.class)
			.single();
	}
}
