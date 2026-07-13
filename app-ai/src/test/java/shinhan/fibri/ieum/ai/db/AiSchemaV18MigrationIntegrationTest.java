package shinhan.fibri.ieum.ai.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class AiSchemaV18MigrationIntegrationTest {

	private static final String CANONICAL_DATABASE = "ieum_ai_v18_schema";
	private static final String MIGRATION_DATABASE = "ieum_ai_v18_migration";
	private static final String PREFLIGHT_DATABASE = "ieum_ai_v18_preflight";
	private static final String HASH_CONSTRAINT = "ck_knowledge_sources_content_hash";

	@AfterAll
	static void cleanUpDatabases() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + CANONICAL_DATABASE + " WITH (FORCE)").update();
		admin.sql("DROP DATABASE IF EXISTS " + MIGRATION_DATABASE + " WITH (FORCE)").update();
		admin.sql("DROP DATABASE IF EXISTS " + PREFLIGHT_DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void canonicalSchemaValidatesLowercaseSha256AtTheWriteBoundary() {
		CanonicalPostgresContainer.recreateDatabase(CANONICAL_DATABASE);
		SqlScriptRunner.run(CANONICAL_DATABASE, "schema.sql");
		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(CANONICAL_DATABASE));

		assertThat(constraintValidated(jdbc, HASH_CONSTRAINT)).isTrue();
		assertThat(insertReadySource(jdbc, "canonical-valid", "a".repeat(64))).isPositive();
		assertThatThrownBy(() -> insertReadySource(jdbc, "canonical-uppercase", "A".repeat(64)))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining(HASH_CONSTRAINT);
		assertThatThrownBy(() -> insertReadySource(jdbc, "canonical-nonhex", "g".repeat(64)))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining(HASH_CONSTRAINT);
		assertThatThrownBy(() -> insertReadySource(jdbc, "canonical-short", "a".repeat(63)))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining(HASH_CONSTRAINT);
	}

	@Test
	void v18MigrationPreservesValidV17SourcesAndValidatesTheConstraint() {
		prepareV17Database(MIGRATION_DATABASE);
		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(MIGRATION_DATABASE));
		long sourceId = insertReadySource(jdbc, "migration-valid", "b".repeat(64));

		SqlScriptRunner.run(MIGRATION_DATABASE, "migrations/v18_knowledge_source_content_hash.sql");

		assertThat(constraintValidated(jdbc, HASH_CONSTRAINT)).isTrue();
		assertThat(jdbc.sql("SELECT content_hash FROM knowledge_sources WHERE source_id = :sourceId")
			.param("sourceId", sourceId)
			.query(String.class)
			.single()
			.trim()).isEqualTo("b".repeat(64));
		assertThatThrownBy(() -> insertReadySource(jdbc, "migration-uppercase", "C".repeat(64)))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining(HASH_CONSTRAINT);
	}

	@Test
	void v18MigrationPreflightRejectsMalformedV17ContentHashWithDiagnostic() {
		prepareV17Database(PREFLIGHT_DATABASE);
		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(PREFLIGHT_DATABASE));
		insertReadySource(jdbc, "preflight-invalid", "z".repeat(64));

		assertThatThrownBy(() -> SqlScriptRunner.run(
			PREFLIGHT_DATABASE,
			"migrations/v18_knowledge_source_content_hash.sql"
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Malformed knowledge_sources.content_hash values block v18")
			.hasMessageContaining("preflight-invalid");
		assertThat(constraintExists(jdbc, HASH_CONSTRAINT)).isFalse();
	}

	private static void prepareV17Database(String database) {
		CanonicalPostgresContainer.recreateDatabase(database);
		SqlScriptRunner.run(
			database,
			"test-baselines/schema-v12.sql",
			"migrations/v13_app_ai_v2_expand.sql",
			"migrations/v14_report_worklist_expand.sql",
			"migrations/v15_question_ai_ticket_notification.sql",
			"migrations/v16_question_ai_finalization_lock.sql",
			"migrations/v17_question_ai_checkpoints.sql"
		);
	}

	private static long insertReadySource(JdbcClient jdbc, String externalRef, String contentHash) {
		return jdbc.sql("""
			INSERT INTO knowledge_sources (
			    source_type, external_ref, content_hash, display_name, status
			)
			VALUES ('curated', :externalRef, :contentHash, :externalRef, 'ready')
			RETURNING source_id
			""")
			.param("externalRef", externalRef)
			.param("contentHash", contentHash)
			.query(Long.class)
			.single();
	}

	private static boolean constraintExists(JdbcClient jdbc, String constraintName) {
		return jdbc.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM pg_constraint
			    WHERE conrelid = 'public.knowledge_sources'::regclass
			      AND conname = :constraintName
			)
			""")
			.param("constraintName", constraintName)
			.query(Boolean.class)
			.single();
	}

	private static boolean constraintValidated(JdbcClient jdbc, String constraintName) {
		return jdbc.sql("""
			SELECT convalidated
			FROM pg_constraint
			WHERE conrelid = 'public.knowledge_sources'::regclass
			  AND conname = :constraintName
			""")
			.param("constraintName", constraintName)
			.query(Boolean.class)
			.single();
	}
}
