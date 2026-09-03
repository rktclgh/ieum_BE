package shinhan.fibri.ieum.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class AiJobOutboxSchemaTest {

	private static final String DATABASE = "ieum_ai_job_outbox_schema";

	private static JdbcClient jdbc;

	@BeforeAll
	static void applyCanonicalSchema() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
	}

	@Test
	void tableExists() {
		assertThat(jdbc.sql("SELECT to_regclass('public.ai_job_outbox') IS NOT NULL")
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void jobIdUniqueConstraintExists() {
		assertThat(constraintExists("uq_ai_job_outbox_job_id")).isTrue();
	}

	@Test
	void statusCheckRejectsUnknownStatus() {
		assertCheckViolation("""
			INSERT INTO ai_job_outbox (job_id, job_type, job_key, routing_key, payload, status)
			VALUES (gen_random_uuid(), 'question_answer_dispatch', 1,
			        'ai.question-answer.dispatch', '{}'::jsonb, 'bogus')
			""");
	}

	@Test
	void jobTypeCheckRejectsUnknownJobType() {
		assertCheckViolation("""
			INSERT INTO ai_job_outbox (job_id, job_type, job_key, routing_key, payload)
			VALUES (gen_random_uuid(), 'not_a_real_job_type', 1,
			        'ai.question-answer.dispatch', '{}'::jsonb)
			""");
	}

	@Test
	void jobKeyPositiveCheckRejectsZero() {
		assertCheckViolation("""
			INSERT INTO ai_job_outbox (job_id, job_type, job_key, routing_key, payload)
			VALUES (gen_random_uuid(), 'question_answer_dispatch', 0,
			        'ai.question-answer.dispatch', '{}'::jsonb)
			""");
	}

	@Test
	void publishingLeaseCheckRejectsPublishingWithoutLease() {
		assertCheckViolation("""
			INSERT INTO ai_job_outbox (job_id, job_type, job_key, routing_key, payload, status)
			VALUES (gen_random_uuid(), 'question_answer_dispatch', 1,
			        'ai.question-answer.dispatch', '{}'::jsonb, 'publishing')
			""");
	}

	@Test
	void publishedAtStatusCheckRejectsPublishedWithoutTimestamp() {
		assertCheckViolation("""
			INSERT INTO ai_job_outbox (job_id, job_type, job_key, routing_key, payload, status, published_at)
			VALUES (gen_random_uuid(), 'question_answer_dispatch', 1,
			        'ai.question-answer.dispatch', '{}'::jsonb, 'published', NULL)
			""");
	}

	@Test
	void claimIndexExists() {
		assertThat(indexExists("idx_ai_job_outbox_claim")).isTrue();
	}

	@Test
	void expiredLeaseIndexExists() {
		assertThat(indexExists("idx_ai_job_outbox_expired_lease")).isTrue();
	}

	@Test
	void retentionIndexExists() {
		assertThat(indexExists("idx_ai_job_outbox_retention")).isTrue();
	}

	private static void assertCheckViolation(String insertSql) {
		DataIntegrityViolationException exception = Assertions.assertThrows(
			DataIntegrityViolationException.class,
			() -> jdbc.sql(insertSql).update());
		Throwable rootCause = NestedExceptionUtils.getRootCause(exception);
		assertThat(rootCause).isInstanceOf(SQLException.class);
		assertThat(((SQLException) rootCause).getSQLState()).isEqualTo("23514");
	}

	private static boolean constraintExists(String constraintName) {
		return jdbc.sql("""
			SELECT EXISTS (
				SELECT 1
				  FROM pg_constraint
				 WHERE conrelid = 'public.ai_job_outbox'::regclass
				   AND conname = :constraintName
			)
			""")
			.param("constraintName", constraintName)
			.query(Boolean.class)
			.single();
	}

	private static boolean indexExists(String indexName) {
		return jdbc.sql("""
			SELECT EXISTS (
				SELECT 1
				  FROM pg_indexes
				 WHERE schemaname = 'public'
				   AND tablename = 'ai_job_outbox'
				   AND indexname = :indexName
			)
			""")
			.param("indexName", indexName)
			.query(Boolean.class)
			.single();
	}
}
