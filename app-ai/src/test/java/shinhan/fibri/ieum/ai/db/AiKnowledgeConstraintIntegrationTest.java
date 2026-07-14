package shinhan.fibri.ieum.ai.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class AiKnowledgeConstraintIntegrationTest {

	private static final String DATABASE = "ieum_ai_knowledge_ck";

	private DataSource dataSource;
	private JdbcClient jdbc;
	private Long firstSourceId;
	private Long firstChunkId;
	private Long secondChunkId;

	@BeforeEach
	void setUpSchema() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = JdbcClient.create(dataSource);
		firstSourceId = insertSource("first-source", "1");
		firstChunkId = insertChunk(firstSourceId, 0, "gemini-embedding-2");
		Long secondSourceId = insertSource("second-source", "2");
		secondChunkId = insertChunk(secondSourceId, 0, "gemini-embedding-2");
	}

	@Test
	void rejectsRelationEvidenceFromDifferentSource() {
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO knowledge_relations (source_id, subject, predicate, object, confidence, evidence_chunk_id)
			VALUES (:sourceId, 'A', 'relates_to', 'B', 0.9000, :evidenceChunkId)
			""").param("sourceId", firstSourceId).param("evidenceChunkId", secondChunkId).update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("fk_knowledge_relations_same_source_evidence");
	}

	@Test
	void acceptsRelationEvidenceFromSameSource() {
		jdbc.sql("""
			INSERT INTO knowledge_relations (source_id, subject, predicate, object, confidence, evidence_chunk_id)
			VALUES (:sourceId, 'C', 'relates_to', 'D', 0.9000, :evidenceChunkId)
			""").param("sourceId", firstSourceId).param("evidenceChunkId", firstChunkId).update();
	}

	@Test
	void rejectsNewNonGeminiEmbeddingChunks() {
		assertThatThrownBy(() -> insertChunk(firstSourceId, 1, "gemini-embedding-001"))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_knowledge_chunks_embedding_model");
	}

	@Test
	void rejectsDuplicateActiveExternalReference() {
		assertThatThrownBy(() -> insertSource("first-source", "1"))
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("uidx_knowledge_source_active_external_ref");
	}

	@Test
	void rejectsPendingSourceWithoutIngestionLease() {
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO knowledge_sources (
				source_type, external_ref, content_hash, display_name, status
			)
			VALUES ('curated', 'pending-source', repeat('4', 64), 'pending source', 'pending')
			""").update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_knowledge_sources_ingestion_lease");
	}

	@Test
	void notifiesPolicyRuleChanges() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			connection.createStatement().execute("LISTEN ai_report_policy_rules_changed");

			jdbc.sql("""
				INSERT INTO ai_report_policy_rules (
					rule_code, title, category, criteria, decision, severity, min_confidence,
					evidence_types, content_hash
				)
				VALUES (
					'ABUSE_HIGH', 'abuse high', 'abuse', 'clear abuse', 'suspend', 'high',
					0.8500, 'text', repeat('a', 64)
				)
				""").update();
			connection.createStatement().execute("SELECT 1");

			PGNotification[] notifications = connection.unwrap(PGConnection.class).getNotifications();
			assertThat(notifications).extracting(PGNotification::getName).contains("ai_report_policy_rules_changed");
		}
	}

	private Long insertSource(String externalRef, String hashDigit) {
		return jdbc.sql("""
			INSERT INTO knowledge_sources (
				source_type, external_ref, content_hash, display_name, status
			)
			VALUES ('curated', :externalRef, repeat(:hashDigit, 64), :externalRef, 'ready')
			RETURNING source_id
			""").param("externalRef", externalRef).param("hashDigit", hashDigit).query(Long.class).single();
	}

	private Long insertChunk(Long sourceId, int chunkOrder, String embeddingModel) {
		return jdbc.sql("""
			INSERT INTO knowledge_chunks (source_id, content, chunk_order, embedding, embedding_model)
			VALUES (:sourceId, 'chunk', :chunkOrder, array_fill(0.0::real, ARRAY[768])::vector, :embeddingModel)
			RETURNING chunk_id
			""")
			.param("sourceId", sourceId)
			.param("chunkOrder", chunkOrder)
			.param("embeddingModel", embeddingModel)
			.query(Long.class)
			.single();
	}
}
