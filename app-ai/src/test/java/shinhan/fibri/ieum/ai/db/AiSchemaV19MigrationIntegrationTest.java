package shinhan.fibri.ieum.ai.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class AiSchemaV19MigrationIntegrationTest {

	private static final String CANONICAL_DATABASE = "ieum_ai_v19_schema";
	private static final String MIGRATION_DATABASE = "ieum_ai_v19_migration";
	private static final String PREFLIGHT_DATABASE = "ieum_ai_v19_preflight";
	private static final String ACTIVE_EXTERNAL_REF_INDEX = "uidx_knowledge_source_active_external_ref";
	private static final String ACTIVE_ANCHOR_INDEX = "idx_knowledge_sources_active_anchor_location";
	private static final String ATTEMPTS_CONSTRAINT = "ck_knowledge_sources_ingestion_attempts";

	@AfterAll
	static void cleanUpDatabases() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + CANONICAL_DATABASE + " WITH (FORCE)").update();
		admin.sql("DROP DATABASE IF EXISTS " + MIGRATION_DATABASE + " WITH (FORCE)").update();
		admin.sql("DROP DATABASE IF EXISTS " + PREFLIGHT_DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void canonicalSchemaAllowsOnlyOneActiveRevisionPerLogicalExternalReference() {
		CanonicalPostgresContainer.recreateDatabase(CANONICAL_DATABASE);
		SqlScriptRunner.run(CANONICAL_DATABASE, "schema.sql");
		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(CANONICAL_DATABASE));

		assertLifecycleSchema(jdbc);
		long previous = insertReadySource(jdbc, "seed:package:source", "a".repeat(64));
		assertThatThrownBy(() -> insertReadySource(jdbc, "seed:package:source", "b".repeat(64)))
			.isInstanceOf(RuntimeException.class)
			.hasMessageContaining(ACTIVE_EXTERNAL_REF_INDEX);

		deactivate(jdbc, previous);

		assertThat(insertReadySource(jdbc, "seed:package:source", "a".repeat(64))).isPositive();
	}

	@Test
	void v19MigrationPreservesV18RowsAndEnablesRevisionReplacement() {
		prepareV18Database(MIGRATION_DATABASE);
		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(MIGRATION_DATABASE));
		long previous = insertReadySource(jdbc, "seed:package:migration", "c".repeat(64));

		SqlScriptRunner.run(MIGRATION_DATABASE, "migrations/v19_knowledge_import_lifecycle.sql");

		assertLifecycleSchema(jdbc);
		assertThat(indexExists(jdbc, "uidx_knowledge_source_external_hash")).isFalse();
		assertThat(jdbc.sql("SELECT count(*) FROM knowledge_sources").query(Integer.class).single()).isOne();
		deactivate(jdbc, previous);
		assertThat(insertReadySource(jdbc, "seed:package:migration", "c".repeat(64))).isPositive();
	}

	@Test
	void v19PreflightRejectsDuplicateActiveLogicalSourcesAndRollsBack() {
		prepareV18Database(PREFLIGHT_DATABASE);
		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(PREFLIGHT_DATABASE));
		insertReadySource(jdbc, "seed:package:duplicate", "d".repeat(64));
		insertReadySource(jdbc, "seed:package:duplicate", "e".repeat(64));

		assertThatThrownBy(() -> SqlScriptRunner.run(
			PREFLIGHT_DATABASE,
			"migrations/v19_knowledge_import_lifecycle.sql"
		))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Duplicate active knowledge source revisions block v19")
			.hasMessageContaining("seed:package:duplicate");
		assertThat(columnExists(jdbc, "knowledge_sources", "ingestion_attempts")).isFalse();
		assertThat(indexExists(jdbc, ACTIVE_EXTERNAL_REF_INDEX)).isFalse();
		assertThat(indexExists(jdbc, "uidx_knowledge_source_external_hash")).isTrue();
	}

	private static void assertLifecycleSchema(JdbcClient jdbc) {
		assertThat(columnExists(jdbc, "knowledge_sources", "ingestion_attempts")).isTrue();
		assertThat(columnExists(jdbc, "knowledge_sources", "next_attempt_at")).isTrue();
		assertThat(constraintValidated(jdbc, ATTEMPTS_CONSTRAINT)).isTrue();
		assertThat(indexExists(jdbc, ACTIVE_EXTERNAL_REF_INDEX)).isTrue();
		assertThat(indexExists(jdbc, ACTIVE_ANCHOR_INDEX)).isTrue();
	}

	private static void prepareV18Database(String database) {
		CanonicalPostgresContainer.recreateDatabase(database);
		SqlScriptRunner.run(
			database,
			"test-baselines/schema-v12.sql",
			"migrations/v13_app_ai_v2_expand.sql",
			"migrations/v14_report_worklist_expand.sql",
			"migrations/v15_question_ai_ticket_notification.sql",
			"migrations/v16_question_ai_finalization_lock.sql",
			"migrations/v17_question_ai_checkpoints.sql",
			"migrations/v18_knowledge_source_content_hash.sql"
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

	private static void deactivate(JdbcClient jdbc, long sourceId) {
		jdbc.sql("UPDATE knowledge_sources SET status = 'inactive' WHERE source_id = :sourceId")
			.param("sourceId", sourceId)
			.update();
	}

	private static boolean columnExists(JdbcClient jdbc, String tableName, String columnName) {
		return jdbc.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM information_schema.columns
			    WHERE table_schema = 'public'
			      AND table_name = :tableName
			      AND column_name = :columnName
			)
			""")
			.param("tableName", tableName)
			.param("columnName", columnName)
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

	private static boolean indexExists(JdbcClient jdbc, String indexName) {
		return jdbc.sql("SELECT to_regclass(:indexName) IS NOT NULL")
			.param("indexName", indexName)
			.query(Boolean.class)
			.single();
	}
}
