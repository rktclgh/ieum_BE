package shinhan.fibri.ieum.main.ai.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.main.ai.outbox.repository.AiJobOutboxRepository;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

/**
 * Task 3 브리프 Done-check: "writer가 @Transactional(propagation = MANDATORY)로 TX 밖 호출을 거부".
 *
 * <p>클래스 레벨 {@code @Transactional(NOT_SUPPORTED)}로 {@code @DataJpaTest}가 기본으로 여는
 * 테스트 트랜잭션을 꺼야만, writer를 트랜잭션 없이 호출했을 때 실제로 MANDATORY가 걸린다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AiJobOutboxWriter.class, AiJobOutboxWriterTransactionBoundaryIntegrationTest.ObjectMapperConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AiJobOutboxWriterTransactionBoundaryIntegrationTest {

	@TestConfiguration
	static class ObjectMapperConfiguration {

		@Bean
		ObjectMapper objectMapper() {
			return new ObjectMapper();
		}
	}

	private static final String DATABASE = "ieum_ai_job_outbox_writer_tx_boundary";

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
	}

	@Autowired
	private AiJobOutboxWriter writer;

	@Autowired
	private AiJobOutboxRepository repository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		// NOT_SUPPORTED이므로 @DataJpaTest의 기본 롤백이 적용되지 않는다 — 각 테스트가 남긴 row가
		// 다음 테스트로 새어나가지 않도록 직접 비운다.
		jdbc.execute("TRUNCATE TABLE ai_job_outbox RESTART IDENTITY CASCADE");
	}

	@Test
	void enqueueQuestionAnswerDispatchOutsideAnyTransactionIsRejected() {
		assertThatThrownBy(() -> writer.enqueueQuestionAnswerDispatch(1L, Reason.CREATED))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(repository.count()).isZero();
	}

	@Test
	void enqueueAcceptedAnswerKnowledgeOutsideAnyTransactionIsRejected() {
		assertThatThrownBy(() -> writer.enqueueAcceptedAnswerKnowledge(1L))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(repository.count()).isZero();
	}

	@Test
	void enqueueQuestionAnswerDispatchInsideAnExistingTransactionSucceeds() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.execute(status -> {
			writer.enqueueQuestionAnswerDispatch(1L, Reason.CREATED);
			return null;
		});
		assertThat(repository.count()).isEqualTo(1L);
	}
}
