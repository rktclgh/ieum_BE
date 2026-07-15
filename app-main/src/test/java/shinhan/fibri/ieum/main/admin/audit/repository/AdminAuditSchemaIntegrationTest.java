package shinhan.fibri.ieum.main.admin.audit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class AdminAuditSchemaIntegrationTest {

	private static final String DATABASE = "ieum_admin_audit_schema";

	private JdbcClient jdbc;

	@BeforeEach
	void recreateDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
	}

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void v26MigrationCreatesAppendOnlyAuditStorageContract() {
		createLegacyUsersTable();

		SqlScriptRunner.run(DATABASE, "migrations/v26_admin_audit_logs.sql");

		assertAuditTableContract();
		assertForeignKeyAndJsonConstraints();
	}

	@Test
	void v26FailureRollsBackTableAndEarlierIndexes() {
		createLegacyUsersTable();
		jdbc.sql("CREATE INDEX idx_admin_audit_logs_created_desc ON users(user_id)").update();

		assertThatThrownBy(() -> SqlScriptRunner.run(DATABASE, "migrations/v26_admin_audit_logs.sql"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("idx_admin_audit_logs_created_desc");

		assertThat(tableExists("admin_audit_logs")).isFalse();
		assertThat(indexNames("users")).containsExactly("idx_admin_audit_logs_created_desc", "users_pkey");
	}

	@Test
	void canonicalSchemaContainsTheSameAuditStorageContract() {
		SqlScriptRunner.run(DATABASE, "schema.sql");

		assertAuditTableContract();
		assertForeignKeyAndJsonConstraints();
	}

	private void assertAuditTableContract() {
		assertThat(columns()).containsExactly(
			"audit_id",
			"actor_user_id",
			"action",
			"target_type",
			"target_id",
			"details",
			"created_at"
		);
		assertThat(nullableColumns()).containsExactly("actor_user_id");
		assertThat(columnMetadata("details"))
			.containsEntry("data_type", "jsonb")
			.containsEntry("is_nullable", "NO");
		assertThat(columnMetadata("target_id"))
			.containsEntry("data_type", "bigint")
			.containsEntry("is_nullable", "NO");
		assertThat(columnMetadata("created_at").get("column_default")).asString().contains("now()");

		assertThat(constraintDefinitions())
			.anySatisfy(definition -> assertThat(definition)
				.contains("FOREIGN KEY (actor_user_id)")
				.contains("REFERENCES users(user_id)")
				.contains("ON DELETE SET NULL"))
			.anySatisfy(definition -> assertThat(definition)
				.contains("jsonb_typeof(details)")
				.contains("'object'"))
			.anySatisfy(definition -> assertThat(definition)
				.contains("target_type")
				.contains("'user'")
				.contains("'report'")
				.contains("'inquiry'"))
			.anySatisfy(definition -> assertThat(definition)
				.contains("action")
				.contains("'USER_SANCTION_CREATED'")
				.contains("'USER_ACTIVATED'")
				.contains("'USER_ROLE_CHANGED'")
				.contains("'REPORT_CONFIRMED'")
				.contains("'REPORT_DISMISSED'")
				.contains("'INQUIRY_ANSWERED'"));

		assertThat(indexDefinitions())
			.anySatisfy(definition -> assertThat(definition)
				.contains("idx_admin_audit_logs_actor_created")
				.contains("(actor_user_id, created_at DESC, audit_id DESC)"))
			.anySatisfy(definition -> assertThat(definition)
				.contains("idx_admin_audit_logs_target_created")
				.contains("(target_type, target_id, created_at DESC, audit_id DESC)"))
			.anySatisfy(definition -> assertThat(definition)
				.contains("idx_admin_audit_logs_created_desc")
				.contains("(created_at DESC, audit_id DESC)"));
	}

	private void assertForeignKeyAndJsonConstraints() {
		long actorId = jdbc.sql("""
			INSERT INTO users(email, nickname)
			VALUES ('audit-admin@example.com', 'audit-admin')
			RETURNING user_id
			""").query(Long.class).single();
		long auditId = jdbc.sql("""
			INSERT INTO admin_audit_logs(actor_user_id, action, target_type, target_id, details)
			VALUES (:actorUserId, 'USER_ROLE_CHANGED', 'user', 20, '{"previousRole":"user","newRole":"admin"}')
			RETURNING audit_id
			""")
			.param("actorUserId", actorId)
			.query(Long.class)
			.single();

		Map<String, Object> row = jdbc.sql("""
			SELECT action, target_type, target_id, details::text AS details, created_at
			FROM admin_audit_logs
			WHERE audit_id = :auditId
			""").param("auditId", auditId).query().singleRow();
		assertThat(row)
			.containsEntry("action", "USER_ROLE_CHANGED")
			.containsEntry("target_type", "user")
			.containsEntry("target_id", 20L);
		assertThat(row.get("details")).asString().contains("previousRole", "newRole");
		assertThat(row.get("created_at")).isNotNull();

		jdbc.sql("DELETE FROM users WHERE user_id = :actorUserId")
			.param("actorUserId", actorId)
			.update();
		assertThat(jdbc.sql("SELECT actor_user_id IS NULL FROM admin_audit_logs WHERE audit_id = :auditId")
			.param("auditId", auditId)
			.query(Boolean.class)
			.single()).isTrue();

		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO admin_audit_logs(action, target_type, target_id, details)
			VALUES ('USER_ROLE_CHANGED', 'account', 20, '{}')
			""").update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_admin_audit_logs_target_type");
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO admin_audit_logs(action, target_type, target_id, details)
			VALUES ('USER_ROLE_CHANGED', 'user', 20, '[]')
			""").update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_admin_audit_logs_details_object");
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO admin_audit_logs(action, target_type, target_id, details)
			VALUES ('USER_EMAIL_EXPORTED', 'user', 20, '{}')
			""").update())
			.isInstanceOf(DataAccessException.class)
			.hasMessageContaining("ck_admin_audit_logs_action");
		assertThatThrownBy(() -> jdbc.sql("""
			INSERT INTO admin_audit_logs(action, target_type, target_id, details)
			VALUES ('USER_ROLE_CHANGED', 'user', 20, CAST('{bad json' AS jsonb))
			""").update())
			.isInstanceOf(DataAccessException.class);
	}

	private void createLegacyUsersTable() {
		jdbc.sql("""
			CREATE TABLE users (
				user_id BIGSERIAL PRIMARY KEY,
				email VARCHAR(255),
				nickname VARCHAR(50) NOT NULL
			)
			""").update();
	}

	private List<String> columns() {
		return jdbc.sql("""
			SELECT column_name
			FROM information_schema.columns
			WHERE table_schema = 'public' AND table_name = 'admin_audit_logs'
			ORDER BY ordinal_position
			""").query(String.class).list();
	}

	private List<String> nullableColumns() {
		return jdbc.sql("""
			SELECT column_name
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'admin_audit_logs'
			  AND is_nullable = 'YES'
			ORDER BY ordinal_position
			""").query(String.class).list();
	}

	private Map<String, Object> columnMetadata(String columnName) {
		return jdbc.sql("""
			SELECT data_type, is_nullable, column_default
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'admin_audit_logs'
			  AND column_name = :columnName
			""").param("columnName", columnName).query().singleRow();
	}

	private List<String> constraintDefinitions() {
		return jdbc.sql("""
			SELECT pg_get_constraintdef(oid)
			FROM pg_constraint
			WHERE conrelid = 'admin_audit_logs'::regclass
			ORDER BY conname
			""").query(String.class).list();
	}

	private List<String> indexDefinitions() {
		return jdbc.sql("""
			SELECT indexdef
			FROM pg_indexes
			WHERE schemaname = 'public' AND tablename = 'admin_audit_logs'
			ORDER BY indexname
			""").query(String.class).list();
	}

	private List<String> indexNames(String tableName) {
		return jdbc.sql("""
			SELECT indexname
			FROM pg_indexes
			WHERE schemaname = 'public' AND tablename = :tableName
			ORDER BY indexname
			""").param("tableName", tableName).query(String.class).list();
	}

	private boolean tableExists(String tableName) {
		return Boolean.TRUE.equals(jdbc.sql("SELECT to_regclass(:tableName) IS NOT NULL")
			.param("tableName", tableName)
			.query(Boolean.class)
			.single());
	}
}
