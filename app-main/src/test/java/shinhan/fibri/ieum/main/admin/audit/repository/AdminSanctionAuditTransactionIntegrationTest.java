package shinhan.fibri.ieum.main.admin.audit.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;
import shinhan.fibri.ieum.main.admin.user.dto.CreateSanctionRequest;
import shinhan.fibri.ieum.main.admin.user.service.AdminSanctionService;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	AdminSanctionService.class,
	AdminAuditLogWriter.class,
	AdminSanctionAuditTransactionIntegrationTest.AuditWriterTestConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminSanctionAuditTransactionIntegrationTest {

	private static final String DATABASE = "ieum_admin_sanction_audit_transaction";

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
	}

	@Autowired
	private AdminSanctionService service;

	@Autowired
	private JdbcTemplate jdbc;

	@MockitoBean
	private RedisAuthSessionStore sessionStore;

	@MockitoBean
	private SseConnectionRegistry sseConnectionRegistry;

	@BeforeEach
	void resetRows() {
		jdbc.execute("DROP TRIGGER IF EXISTS trg_reject_admin_audit_insert ON admin_audit_logs");
		jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
		jdbc.update("""
			INSERT INTO users(user_id, email, password_hash, nickname, email_verified, role, status)
			VALUES
				(1, 'admin@example.com', 'hash', 'admin', TRUE, 'admin', 'active'),
				(10, 'target@example.com', 'hash', 'target', TRUE, 'user', 'active')
			""");
	}

	@Test
	void auditInsertFailureRollsBackJpaDomainChangesAndSkipsAfterCommitCleanup() {
		jdbc.execute("""
			CREATE OR REPLACE FUNCTION reject_admin_audit_insert()
			RETURNS TRIGGER
			LANGUAGE plpgsql
			AS $$
			BEGIN
				RAISE EXCEPTION 'forced administrator audit failure';
			END
			$$
			""");
		jdbc.execute("""
			CREATE TRIGGER trg_reject_admin_audit_insert
			BEFORE INSERT ON admin_audit_logs
			FOR EACH ROW EXECUTE FUNCTION reject_admin_audit_insert()
			""");

		assertThatThrownBy(() -> service.sanction(
			new AuthenticatedUser(1L, "admin@example.com", UserRole.admin, UserStatus.active),
			10L,
			new CreateSanctionRequest(SanctionType.permanent, "abuse", null)
		)).isInstanceOf(DataAccessException.class)
			.hasMessageContaining("forced administrator audit failure");

		assertThat(jdbc.queryForObject("SELECT status::text FROM users WHERE user_id = 10", String.class))
			.isEqualTo("active");
		assertThat(jdbc.queryForObject("SELECT auth_version FROM users WHERE user_id = 10", Long.class))
			.isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM user_sanctions", Long.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_logs", Long.class)).isZero();
		verify(sessionStore, never()).revokeAllSessionsOfUser(10L);
		verify(sseConnectionRegistry, never()).closeUser(10L);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class AuditWriterTestConfiguration {

		@Bean
		JdbcClient jdbcClient(DataSource dataSource) {
			return JdbcClient.create(dataSource);
		}

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper().findAndRegisterModules();
		}
	}
}
