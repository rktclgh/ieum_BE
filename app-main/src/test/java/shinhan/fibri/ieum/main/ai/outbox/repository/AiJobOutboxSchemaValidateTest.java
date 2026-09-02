package shinhan.fibri.ieum.main.ai.outbox.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import shinhan.fibri.ieum.main.ai.outbox.entity.AiJobOutbox;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

/**
 * {@code AiJobOutbox} 엔티티가 <b>정본 스키마</b>({@code db/schema.sql})와 맞는지 {@code ddl-auto=validate}로
 * 확인한다. Task 3 브리프 Done-check: "ddl-auto=validate 컨텍스트 기동 성공(엔티티/DDL 일치 증명)".
 *
 * <p>스캔을 {@code AiJobOutbox}로 좁힌 이유는 {@code NotificationSchemaValidateIntegrationTest}와 같다 —
 * 이 레포에는 이미 정본 스키마와 어긋난 선행 엔티티가 있어(answer_images.sort_order 등) 전체 스캔은
 * 이 변경분과 무관한 이유로 먼저 실패할 수 있다. 그건 이 작업 범위 밖의 선행 이슈다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EntityScan(basePackageClasses = AiJobOutbox.class)
@EnableJpaRepositories(basePackageClasses = AiJobOutboxRepository.class)
class AiJobOutboxSchemaValidateTest {

	private static final String DATABASE = "ieum_ai_job_outbox_validate";

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
		// 이 테스트의 존재 이유. none으로 바꾸면 검증력이 사라진다.
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
	}

	@Autowired
	private AiJobOutboxRepository repository;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@Test
	void bootsWithValidateAgainstCanonicalSchema() {
		// 이 메서드가 실행됐다는 사실 자체가 검증 결과다. 매핑이 스키마와 어긋나면 Hibernate가
		// SchemaManagementException으로 컨텍스트 로드를 실패시켜 여기 도달하지 못한다.
		assertThat(repository).isNotNull();
	}

	@Test
	void persistsAndReadsBackThroughTheValidatedMapping() {
		UUID jobId = UUID.randomUUID();
		AiJobOutbox saved = repository.saveAndFlush(AiJobOutbox.pending(
			jobId, "question_answer_dispatch", 1L, "ai.question-answer.dispatch", "{\"questionId\":1}"
		));

		assertThat(repository.findById(saved.getId()).orElseThrow().getJobId()).isEqualTo(jobId);
	}
}
