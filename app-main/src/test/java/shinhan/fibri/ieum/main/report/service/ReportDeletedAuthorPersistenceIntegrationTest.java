package shinhan.fibri.ieum.main.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.report.domain.ReportContextSnapshot;
import shinhan.fibri.ieum.main.report.domain.ReportReason;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(ReportService.class)
class ReportDeletedAuthorPersistenceIntegrationTest {

	private static final String DATABASE = "ieum_report_deleted_author";

	static {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
	}

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> CanonicalPostgresContainer.jdbcUrl(DATABASE));
		registry.add("spring.datasource.username", CanonicalPostgresContainer::username);
		registry.add("spring.datasource.password", CanonicalPostgresContainer::password);
		registry.add("spring.datasource.driver-class-name", CanonicalPostgresContainer::driverClassName);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
	}

	@Autowired
	private ReportService service;

	@Autowired
	private JdbcTemplate jdbc;

	@MockitoBean
	private ReportContextSnapshotFactory snapshotFactory;

	private long reporterId;
	private long answerId;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
		reporterId = insertUser("reporter@example.com", "reporter", null);
		long deletedAuthorId = insertUser("deleted@example.com", "deleted", "2026-07-13T10:00:00Z");
		long pinId = jdbc.queryForObject("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (?, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울')
			RETURNING pin_id
			""", Long.class, reporterId);
		long questionId = jdbc.queryForObject("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (?, ?, 'question', 'content')
			RETURNING question_id
			""", Long.class, pinId, reporterId);
		answerId = jdbc.queryForObject("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (?, ?, FALSE, 'human answer')
			RETURNING answer_id
			""", Long.class, questionId, deletedAuthorId);
		when(snapshotFactory.createAnswer(any(Answer.class), anyList()))
			.thenReturn(new ReportContextSnapshot("{\"schemaVersion\":1,\"targetType\":\"answer\"}", "c".repeat(64)));
	}

	@Test
	void persistsReportedUserForHumanAnswerWhoseAuthorIsSoftDeleted() {
		long reportId = service.createAnswer(
			new AuthenticatedUser(reporterId, "reporter@example.com", UserRole.user, UserStatus.active),
			answerId,
			ReportReason.abuse,
			null
		).reportId();

		assertThat(jdbc.queryForObject(
			"SELECT reported_user_id FROM reports WHERE report_id = ?",
			Long.class,
			reportId
		)).isEqualTo(2L);
	}

	private long insertUser(String email, String nickname, String deletedAt) {
		return jdbc.queryForObject("""
			INSERT INTO users (email, password_hash, nickname, email_verified, deleted_at)
			VALUES (?, 'hash', ?, TRUE, CAST(? AS timestamptz))
			RETURNING user_id
			""", Long.class, email, nickname, deletedAt);
	}
}
