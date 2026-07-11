package shinhan.fibri.ieum.ai.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.ai.support.AiPostgresContainer;
import shinhan.fibri.ieum.ai.support.SqlScriptRunner;

class AiSchemaV13MigrationIntegrationTest {

	@Test
	void upgradesV12AiTablesWithoutLosingRows() {
		String database = "ieum_ai_v13_migration";
		AiPostgresContainer.recreateDatabase(database);
		SqlScriptRunner.run(database, "test-baselines/schema-v12.sql");
		JdbcClient jdbc = JdbcClient.create(AiPostgresContainer.dataSource(database));
		Fixture fixture = insertV12Rows(jdbc);
		List<String> reportColumnsBefore = columns(jdbc, "reports");
		List<String> sanctionColumnsBefore = columns(jdbc, "user_sanctions");

		SqlScriptRunner.run(database, "migrations/v13_app_ai_v2_expand.sql");

		assertThat(jdbc.sql("SELECT gen_random_uuid() IS NOT NULL").query(Boolean.class).single()).isTrue();
		assertThat(columns(jdbc, "ai_question_tasks")).contains(
			"lease_token",
			"geo_scope",
			"geo_scope_confidence",
			"region_context",
			"answer_outcome",
			"generation_provider",
			"retrieval_config_version",
			"fallback_reason",
			"updated_at");
		assertThat(columns(jdbc, "knowledge_sources")).contains(
			"display_name",
			"status",
			"last_error_code",
			"last_error_message",
			"deactivation_reason",
			"ingestion_token",
			"ingestion_lease_until",
			"geo_scope",
			"region_context",
			"anchor_location",
			"valid_until",
			"metadata",
			"created_by",
			"updated_by",
			"updated_at");
		assertThat(columns(jdbc, "knowledge_relations")).contains("evidence_chunk_id");
		assertThat(tableExists(jdbc, "ai_report_policy_rules")).isTrue();
		assertThat(indexExists(jdbc, "idx_knowledge_sources_expired_ingestion")).isTrue();
		assertThat(indexExists(jdbc, "uidx_knowledge_source_external_hash")).isTrue();
		assertThat(indexExists(jdbc, "idx_ai_report_policy_rules_snapshot")).isTrue();
		assertThat(triggerExists(jdbc, "trg_ai_report_policy_rules_notify")).isTrue();
		assertThat(triggerExists(jdbc, "trg_knowledge_source_active_compat")).isTrue();

		assertThat(jdbc.sql("""
			SELECT answer_outcome, generation_provider, generation_model, jsonb_array_length(evidence)
			FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", fixture.questionId())
			.query((rs, rowNumber) -> new CompletedTaskBackfill(
				rs.getString("answer_outcome"),
				rs.getString("generation_provider"),
				rs.getString("generation_model"),
				rs.getInt(4)))
			.single())
			.isEqualTo(new CompletedTaskBackfill("local_grounded", "legacy_v12", "gemini-2.5-flash", 1));
		assertThat(jdbc.sql("""
			SELECT status
			FROM knowledge_sources
			WHERE source_id = :sourceId
			""")
			.param("sourceId", fixture.readySourceId())
			.query(String.class)
			.single()).isEqualTo("ready");
		assertThat(jdbc.sql("""
			SELECT status
			FROM knowledge_sources
			WHERE source_id = :sourceId
			""")
			.param("sourceId", fixture.inactiveSourceId())
			.query(String.class)
			.single()).isEqualTo("inactive");
		assertThat(jdbc.sql("SELECT count(*) FROM ai_question_tasks").query(Integer.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM knowledge_sources").query(Integer.class).single()).isEqualTo(3);
		assertThat(jdbc.sql("SELECT count(*) FROM knowledge_chunks").query(Integer.class).single()).isEqualTo(3);
		assertThat(jdbc.sql("""
			SELECT status, active, deactivated_at IS NOT NULL
			FROM knowledge_sources
			WHERE source_id = :sourceId
			""")
			.param("sourceId", fixture.activeLegacySourceId())
			.query((rs, rowNumber) -> new LegacySourceState(
				rs.getString("status"), rs.getBoolean("active"), rs.getBoolean(3)))
			.single())
			.isEqualTo(new LegacySourceState("inactive", false, true));
		assertThat(columns(jdbc, "reports")).isEqualTo(reportColumnsBefore);
		assertThat(columns(jdbc, "user_sanctions")).isEqualTo(sanctionColumnsBefore);
	}

	@Test
	void failsWithDiagnosticWhenExternalReferenceHashesAreDuplicated() {
		String database = "ieum_ai_v13_dups";
		AiPostgresContainer.recreateDatabase(database);
		SqlScriptRunner.run(database, "test-baselines/schema-v12.sql");
		JdbcClient jdbc = JdbcClient.create(AiPostgresContainer.dataSource(database));

		insertV12Source(jdbc, "duplicate-source", "d", true);
		insertV12Source(jdbc, "duplicate-source", "d", true);

		assertThatThrownBy(() -> SqlScriptRunner.run(database, "migrations/v13_app_ai_v2_expand.sql"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("Duplicate knowledge_sources external_ref/content_hash triples block v13");
	}

	private static Fixture insertV12Rows(JdbcClient jdbc) {
		Long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES ('ai-v13@example.com', 'hash', 'ai-v13-user', true)
			RETURNING user_id
			""").query(Long.class).single();
		Long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (:userId, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, 'Seoul')
			RETURNING pin_id
			""").param("userId", userId).query(Long.class).single();
		Long questionId = jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (:pinId, :userId, 'title', 'content')
			RETURNING question_id
			""").param("pinId", pinId).param("userId", userId).query(Long.class).single();
		Long answerId = jdbc.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (:questionId, NULL, true, 'answer')
			RETURNING answer_id
			""").param("questionId", questionId).query(Long.class).single();
		jdbc.sql("""
			INSERT INTO ai_question_tasks (
				question_id, status, stage, embedding, embedding_model, answer_id,
				generation_model, grounding_status, grounding_score, evidence, completed_at
			)
			VALUES (
				:questionId, 'completed', 'persisting', array_fill(0.0::real, ARRAY[768])::vector,
				'gemini-embedding-2', :answerId, 'gemini-2.5-flash', 'grounded', 0.9000,
				'[{"type":"legacy_v12","id":1}]'::jsonb, now()
			)
			""").param("questionId", questionId).param("answerId", answerId).update();
		Long readySourceId = insertV12Source(jdbc, "ready-source", "a", true);
		jdbc.sql("""
			INSERT INTO knowledge_chunks (source_id, content, embedding, embedding_model)
			VALUES (:sourceId, 'ready chunk', array_fill(0.0::real, ARRAY[768])::vector, 'gemini-embedding-2')
			""").param("sourceId", readySourceId).update();
		Long inactiveSourceId = insertV12Source(jdbc, "inactive-source", "b", false);
		jdbc.sql("""
			INSERT INTO knowledge_chunks (source_id, content, embedding, embedding_model)
			VALUES (:sourceId, 'legacy chunk', array_fill(0.0::real, ARRAY[768])::vector, 'gemini-embedding-001')
			""").param("sourceId", inactiveSourceId).update();
		Long activeLegacySourceId = insertV12Source(jdbc, "active-legacy-source", "c", true);
		jdbc.sql("""
			INSERT INTO knowledge_chunks (source_id, content, embedding, embedding_model)
			VALUES (:sourceId, 'active legacy chunk', array_fill(0.0::real, ARRAY[768])::vector, 'gemini-embedding-001')
			""").param("sourceId", activeLegacySourceId).update();
		return new Fixture(questionId, readySourceId, inactiveSourceId, activeLegacySourceId);
	}

	private static Long insertV12Source(JdbcClient jdbc, String externalRef, String hashDigit, boolean active) {
		return jdbc.sql("""
			INSERT INTO knowledge_sources (source_type, external_ref, content_hash, active, deactivated_at)
			VALUES ('curated', :externalRef, repeat(:hashDigit, 64), :active, CASE WHEN :active THEN NULL ELSE now() END)
			RETURNING source_id
			""")
			.param("externalRef", externalRef)
			.param("hashDigit", hashDigit)
			.param("active", active)
			.query(Long.class)
			.single();
	}

	private static List<String> columns(JdbcClient jdbc, String tableName) {
		return jdbc.sql("""
			SELECT column_name
			FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = :tableName
			ORDER BY ordinal_position
			""").param("tableName", tableName).query(String.class).list();
	}

	private static boolean tableExists(JdbcClient jdbc, String tableName) {
		return jdbc.sql("SELECT to_regclass(:tableName) IS NOT NULL")
			.param("tableName", tableName)
			.query(Boolean.class)
			.single();
	}

	private static boolean indexExists(JdbcClient jdbc, String indexName) {
		return jdbc.sql("SELECT to_regclass(:indexName) IS NOT NULL")
			.param("indexName", indexName)
			.query(Boolean.class)
			.single();
	}

	private static boolean triggerExists(JdbcClient jdbc, String triggerName) {
		return jdbc.sql("""
			SELECT EXISTS (
				SELECT 1
				FROM pg_trigger
				WHERE tgname = :triggerName
			)
			""").param("triggerName", triggerName).query(Boolean.class).single();
	}

	private record Fixture(Long questionId, Long readySourceId, Long inactiveSourceId, Long activeLegacySourceId) {
	}

	private record CompletedTaskBackfill(
		String answerOutcome,
		String generationProvider,
		String generationModel,
		int evidenceCount
	) {
	}

	private record LegacySourceState(String status, boolean active, boolean deactivated) {
	}
}
