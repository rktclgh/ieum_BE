package shinhan.fibri.ieum.main.question.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.ai.outbox.service.AiJobOutboxWriter;
import shinhan.fibri.ieum.main.ai.outbox.service.NoOpAiJobOutboxWriter;
import shinhan.fibri.ieum.main.ai.question.repository.JdbcQuestionAnswerTicketWriter;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;
import shinhan.fibri.ieum.main.pin.repository.JdbcPinWriter;
import shinhan.fibri.ieum.main.question.dto.QuestionCreateRequest;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

/**
 * Task 8 브리프 Done-check: {@code app.ai.dispatch.transport=http}(기본값)에서 {@code create()}가
 * outbox row 를 만들지 않는다(고아 row 방지) — 실제 Spring 조건부 배선으로 {@link AiJobOutboxWriter}가
 * {@link NoOpAiJobOutboxWriter}로 해석되는지까지 함께 증명한다({@code NoOpAiJobOutboxWriter}를 직접
 * {@code @Import}하지 않고, 프로퍼티가 없을 때의 기본값 배선을 그대로 태운다).
 *
 * <p>{@code AnswerFinalizationConcurrencyIntegrationTest}와 같은 패턴 — {@code @DataJpaTest} +
 * Testcontainers Postgres + 실제 JDBC 기반 writer(PinWriter/QuestionAnswerTicketWriter)를
 * {@code @Import}해 {@link QuestionService#create}를 끝단까지 실행한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	QuestionService.class,
	QuestionDeletionExecutor.class,
	JdbcPinWriter.class,
	JdbcQuestionAnswerTicketWriter.class,
	NoOpAiJobOutboxWriter.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class QuestionServiceHttpModeTest {

	private static final String DATABASE = "ieum_question_service_http_mode";

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
		// app.ai.dispatch.transport 를 명시적으로 지정하지 않는다 — 이 테스트가 증명하려는 것은
		// "값을 아무 것도 안 건드린 기본 배포 상태(http)"에서 outbox 가 비는지다.
	}

	@Autowired
	private QuestionService questionService;

	@Autowired
	private AiJobOutboxWriter aiJobOutboxWriter;

	@Autowired
	private JdbcTemplate jdbc;

	private long authorId;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
		jdbc.execute("TRUNCATE TABLE ai_job_outbox RESTART IDENTITY CASCADE");
		authorId = jdbc.queryForObject("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES ('http-mode-author@example.com', 'hash', 'http-mode-author', true)
			RETURNING user_id
			""", Long.class);
	}

	@Test
	void wiresTheNoOpWriterWhenTransportIsUnset() {
		assertThat(aiJobOutboxWriter).isInstanceOf(NoOpAiJobOutboxWriter.class);
	}

	@Test
	void createDoesNotWriteAnOutboxRowInHttpMode() {
		questionService.create(
			principal(authorId),
			new QuestionCreateRequest(
				"http mode question",
				"content",
				new LocationSnapshot(37.5, 127.0, "서울특별시", null, null),
				List.of()
			)
		);

		Integer outboxRowCount = jdbc.queryForObject("SELECT count(*) FROM ai_job_outbox", Integer.class);
		assertThat(outboxRowCount).isZero();

		Integer questionCount = jdbc.queryForObject(
			"SELECT count(*) FROM questions WHERE author_id = ?", Integer.class, authorId
		);
		assertThat(questionCount).isEqualTo(1);
	}

	private AuthenticatedUser principal(long userId) {
		return new AuthenticatedUser(userId, "http-mode-author@example.com", UserRole.user, UserStatus.active);
	}
}
