package shinhan.fibri.ieum.main.admin.audit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class AdminAuditLogWriterIntegrationTest {

	private static final String DATABASE = "ieum_admin_audit_writer";

	private static JdbcClient jdbc;
	private static AdminAuditLogWriter writer;
	private static TransactionTemplate transaction;

	@BeforeAll
	static void setUpDatabase() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		var dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = JdbcClient.create(dataSource);
		writer = new AdminAuditLogWriter(jdbc, new ObjectMapper().findAndRegisterModules());
		transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
	}

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)").update();
	}

	@BeforeEach
	void resetRows() {
		jdbc.sql("TRUNCATE TABLE users RESTART IDENTITY CASCADE").update();
	}

	@Test
	void appendCommitsExactActorActionTargetAndObjectDetails() {
		long actorId = insertActor();

		transaction.executeWithoutResult(status -> writer.append(
			actorId,
			AdminAuditAction.USER_ROLE_CHANGED,
			"user",
			20L,
			Map.of("previousRole", "user", "newRole", "admin")
		));

		Map<String, Object> row = jdbc.sql("""
			SELECT actor_user_id, action, target_type, target_id, details::text AS details, created_at
			FROM admin_audit_logs
			""").query().singleRow();
		assertThat(row)
			.containsEntry("actor_user_id", actorId)
			.containsEntry("action", "USER_ROLE_CHANGED")
			.containsEntry("target_type", "user")
			.containsEntry("target_id", 20L);
		assertThat(row.get("details")).asString().contains("previousRole", "newRole", "user", "admin");
		assertThat(row.get("created_at")).isNotNull();
	}

	@Test
	void callerRollbackRemovesTheAuditInsert() {
		long actorId = insertActor();

		assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
			writer.append(
				actorId,
				AdminAuditAction.REPORT_CONFIRMED,
				"report",
				30L,
				Map.of("previousDecision", "pending", "newDecision", "confirmed")
			);
			throw new IllegalStateException("force caller rollback");
		})).isInstanceOf(IllegalStateException.class)
			.hasMessage("force caller rollback");

		assertThat(auditCount()).isZero();
	}

	@Test
	void serializationFailureThrowsAndPersistsNothing() {
		long actorId = insertActor();

		assertThatThrownBy(() -> writer.append(
			actorId,
			AdminAuditAction.INQUIRY_ANSWERED,
			"inquiry",
			40L,
			Map.of("answerLength", new BrokenNumber())
		)).isInstanceOf(IllegalStateException.class)
			.hasMessage("Failed to serialize administrator audit details");

		assertThat(auditCount()).isZero();
	}

	@Test
	void detailsThatSerializeAsNonObjectAreRejected() {
		long actorId = insertActor();

		assertThatThrownBy(() -> writer.append(
			actorId,
			AdminAuditAction.USER_ROLE_CHANGED,
			"user",
			20L,
			new ArraySerializedRoleDetails()
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Administrator audit details must serialize to a JSON object");

		assertThat(auditCount()).isZero();
	}

	@Test
	void targetAndDetailAllowlistsRejectUnexpectedValues() {
		long actorId = insertActor();

		assertThatThrownBy(() -> writer.append(
			actorId,
			AdminAuditAction.USER_ROLE_CHANGED,
			"account",
			20L,
			Map.of("previousRole", "user", "newRole", "admin")
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("target type");
		assertThatThrownBy(() -> writer.append(
			actorId,
			AdminAuditAction.INQUIRY_ANSWERED,
			"inquiry",
			40L,
			Map.of("answerLength", 10, "answer", "private inquiry text")
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("detail keys");
		assertThatThrownBy(() -> writer.append(
			actorId,
			AdminAuditAction.INQUIRY_ANSWERED,
			"inquiry",
			40L,
			Map.of("answerLength", Map.of("nested", 10))
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("non-primitive value");

		assertThat(auditCount()).isZero();
	}

	@Test
	void publicApplicationApiExposesAppendOnly() {
		assertThat(Arrays.stream(AdminAuditLogWriter.class.getDeclaredMethods())
			.filter(method -> Modifier.isPublic(method.getModifiers()))
			.map(java.lang.reflect.Method::getName))
			.containsExactly("append");
	}

	private static long insertActor() {
		return jdbc.sql("""
			INSERT INTO users(email, password_hash, nickname, email_verified, role)
			VALUES ('writer-admin@example.com', 'hash', 'writer-admin', TRUE, 'admin')
			RETURNING user_id
			""").query(Long.class).single();
	}

	private static long auditCount() {
		return jdbc.sql("SELECT count(*) FROM admin_audit_logs").query(Long.class).single();
	}

	private static final class BrokenNumber extends Number {

		@Override
		public int intValue() {
			throw serializationFailure();
		}

		@Override
		public long longValue() {
			throw serializationFailure();
		}

		@Override
		public float floatValue() {
			throw serializationFailure();
		}

		@Override
		public double doubleValue() {
			throw serializationFailure();
		}

		@Override
		public String toString() {
			throw serializationFailure();
		}

		private IllegalStateException serializationFailure() {
			return new IllegalStateException("broken number");
		}
	}

	private static final class ArraySerializedRoleDetails extends LinkedHashMap<String, Object> {

		private ArraySerializedRoleDetails() {
			put("previousRole", "user");
			put("newRole", "admin");
		}

		@JsonValue
		List<String> serializedValue() {
			return List.of("not-an-object");
		}
	}
}
