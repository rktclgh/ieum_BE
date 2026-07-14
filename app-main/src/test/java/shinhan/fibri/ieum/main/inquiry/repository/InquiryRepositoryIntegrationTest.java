package shinhan.fibri.ieum.main.inquiry.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class InquiryRepositoryIntegrationTest {

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
		DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres")
	);

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> postgres.getJdbcUrl() + "&prepareThreshold=0");
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.datasource.driver-class-name", postgres::getDriverClassName);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
		registry.add(
			"spring.jpa.properties.hibernate.dialect",
			() -> "shinhan.fibri.ieum.common.config.SnakeCasePostgreSQLDialect"
		);
	}

	@Autowired
	private InquiryRepository inquiryRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private ExecutorService executor;

	@BeforeEach
	void setUpSchemaAndRows() {
		executor = Executors.newFixedThreadPool(2);
		createSchema();
		jdbcTemplate.update("TRUNCATE TABLE inquiries RESTART IDENTITY");
		jdbcTemplate.update("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
		jdbcTemplate.update("INSERT INTO users (user_id) VALUES (42)");
		jdbcTemplate.update("""
			INSERT INTO inquiries (inquiry_id, user_id, title, content, status)
			VALUES (90, 42, '문의 제목', '문의 내용', 'pending'::inquiry_status)
			""");
	}

	@AfterEach
	void tearDownExecutor() {
		executor.shutdownNow();
	}

	@Test
	void findByIdForUpdateBlocksConcurrentLockUntilFirstTransactionCommits() throws Exception {
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		CountDownLatch firstLockAcquired = new CountDownLatch(1);
		CountDownLatch releaseFirstTransaction = new CountDownLatch(1);

		Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
			inquiryRepository.findByIdForUpdate(90L).orElseThrow();
			firstLockAcquired.countDown();
			await(releaseFirstTransaction);
		}));
		assertThat(firstLockAcquired.await(5, TimeUnit.SECONDS)).isTrue();

		Future<?> second = executor.submit(() -> transactionTemplate.executeWithoutResult(status ->
			inquiryRepository.findByIdForUpdate(90L).orElseThrow()
		));

		Thread.sleep(300);
		assertThat(second.isDone()).isFalse();

		releaseFirstTransaction.countDown();
		first.get(5, TimeUnit.SECONDS);
		second.get(5, TimeUnit.SECONDS);
	}

	private void createSchema() {
		jdbcTemplate.execute("""
			DO $$
			BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'inquiry_status') THEN
					CREATE TYPE inquiry_status AS ENUM ('pending', 'answered');
				END IF;
			END
			$$
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS users (
				user_id BIGINT PRIMARY KEY
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS inquiries (
				inquiry_id  bigserial PRIMARY KEY,
				user_id     bigint         NOT NULL REFERENCES users (user_id) ON DELETE CASCADE,
				title       varchar(200)   NOT NULL,
				content     text           NOT NULL,
				status      inquiry_status NOT NULL DEFAULT 'pending',
				answer      text,
				answered_by bigint         REFERENCES users (user_id),
				created_at  timestamptz    NOT NULL DEFAULT now(),
				answered_at timestamptz
			)
			""");
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting for latch");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(exception);
		}
	}
}
