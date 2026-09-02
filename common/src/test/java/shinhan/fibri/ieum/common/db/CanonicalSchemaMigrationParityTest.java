package shinhan.fibri.ieum.common.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

/**
 * schema.sql 전체를 적용한 DB와, 해당 테이블을 만드는 증분 마이그레이션 파일 하나만 적용한 DB가
 * 동일한 컬럼·제약·인덱스를 갖는지 검증한다 (SSOT와 증분 파일의 정합성).
 */
class CanonicalSchemaMigrationParityTest {

	@Test
	void aiJobOutboxMatchesBetweenStandaloneMigrationAndFullSchema() {
		String migrationOnlyDatabase = "ieum_ai_job_outbox_v42_only";
		String fullSchemaDatabase = "ieum_ai_job_outbox_full_schema";

		CanonicalPostgresContainer.recreateDatabase(migrationOnlyDatabase);
		SqlScriptRunner.run(migrationOnlyDatabase, "migrations/v42_ai_job_outbox.sql");
		JdbcClient migrationOnlyJdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(migrationOnlyDatabase));

		CanonicalPostgresContainer.recreateDatabase(fullSchemaDatabase);
		SqlScriptRunner.run(fullSchemaDatabase, "schema.sql");
		JdbcClient fullSchemaJdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(fullSchemaDatabase));

		assertThat(columnSignatures(fullSchemaJdbc)).isEqualTo(columnSignatures(migrationOnlyJdbc));
		assertThat(constraintSignatures(fullSchemaJdbc)).isEqualTo(constraintSignatures(migrationOnlyJdbc));
		assertThat(indexSignatures(fullSchemaJdbc)).isEqualTo(indexSignatures(migrationOnlyJdbc));
	}

	@Test
	void v42AiJobOutboxMigrationIsIdempotent() {
		String database = "ieum_ai_job_outbox_v42_idempotent";
		CanonicalPostgresContainer.recreateDatabase(database);

		SqlScriptRunner.run(database, "migrations/v42_ai_job_outbox.sql");
		SqlScriptRunner.run(database, "migrations/v42_ai_job_outbox.sql");

		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(database));
		assertThat(jdbc.sql("SELECT to_regclass('public.ai_job_outbox') IS NOT NULL")
			.query(Boolean.class)
			.single()).isTrue();
	}

	private static List<String> columnSignatures(JdbcClient jdbc) {
		return jdbc.sql("""
			SELECT column_name || ':' || data_type || ':' || is_nullable || ':' || COALESCE(column_default, '<none>')
			  FROM information_schema.columns
			 WHERE table_schema = 'public'
			   AND table_name = 'ai_job_outbox'
			 ORDER BY ordinal_position
			""")
			.query(String.class)
			.list();
	}

	private static List<String> constraintSignatures(JdbcClient jdbc) {
		return jdbc.sql("""
			SELECT conname || ':' || pg_get_constraintdef(oid)
			  FROM pg_constraint
			 WHERE conrelid = 'public.ai_job_outbox'::regclass
			 ORDER BY conname
			""")
			.query(String.class)
			.list();
	}

	private static List<String> indexSignatures(JdbcClient jdbc) {
		return jdbc.sql("""
			SELECT indexname || ':' || indexdef
			  FROM pg_indexes
			 WHERE schemaname = 'public'
			   AND tablename = 'ai_job_outbox'
			 ORDER BY indexname
			""")
			.query(String.class)
			.list();
	}
}
