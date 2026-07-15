package shinhan.fibri.ieum.main.admin.inquiry.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import shinhan.fibri.ieum.main.admin.inquiry.dto.AdminInquiryItem;
import shinhan.fibri.ieum.main.inquiry.domain.InquiryStatus;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminInquiryQueryRepositoryIntegrationTest {

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
	private AdminInquiryQueryRepository repository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUpSchemaAndRows() {
		createSchema();
		jdbcTemplate.update("TRUNCATE TABLE inquiries RESTART IDENTITY");
		jdbcTemplate.update("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
		insertUser(42L, "pending@example.com");
		insertUser(43L, "answered@example.com");
		insertUser(99L, "admin@example.com");
		insertInquiry(90L, 42L, "pending", null, null, null, "2026-07-13T10:00:00+09:00");
		insertInquiry(91L, 43L, "answered", "답변", 99L, "2026-07-13T12:00:00+09:00", "2026-07-13T11:00:00+09:00");
	}

	@Test
	void findAdminItemsFiltersByPostgresEnumStatusAndProjectsJoinedUserEmail() {
		List<AdminInquiryItem> items = repository.findAdminItems(InquiryStatus.pending, null, 20);

		assertThat(items).singleElement().satisfies(item -> {
			assertThat(item.inquiryId()).isEqualTo(90L);
			assertThat(item.userId()).isEqualTo(42L);
			assertThat(item.userEmail()).isEqualTo("pending@example.com");
			assertThat(item.title()).isEqualTo("문의 90");
			assertThat(item.content()).isEqualTo("내용 90");
			assertThat(item.status()).isEqualTo(InquiryStatus.pending);
			assertThat(item.answer()).isNull();
			assertThat(item.answeredBy()).isNull();
			assertThat(item.answeredAt()).isNull();
			assertThat(item.createdAt()).isEqualTo(OffsetDateTime.parse("2026-07-13T10:00:00+09:00"));
		});
	}

	@Test
	void findAdminItemsReturnsAllStatusesWithTenArgumentProjectionWhenStatusIsNull() {
		List<AdminInquiryItem> items = repository.findAdminItems(null, null, 20);

		assertThat(items).hasSize(2);
		assertThat(items).extracting(AdminInquiryItem::inquiryId).containsExactly(91L, 90L);
		assertThat(items.getFirst()).satisfies(item -> {
			assertThat(item.userEmail()).isEqualTo("answered@example.com");
			assertThat(item.status()).isEqualTo(InquiryStatus.answered);
			assertThat(item.answer()).isEqualTo("답변");
			assertThat(item.answeredBy()).isEqualTo(99L);
			assertThat(item.answeredAt()).isEqualTo(OffsetDateTime.parse("2026-07-13T12:00:00+09:00"));
		});
	}

	@Test
	void findAdminItemsAppliesCursorAndLimit() {
		List<AdminInquiryItem> items = repository.findAdminItems(null, 91L, 1);

		assertThat(items).hasSize(1);
		assertThat(items.getFirst().inquiryId()).isEqualTo(90L);
	}

	@Test
	void findAdminItemsKeepsInquiryWhenUserRowIsMissing() {
		insertInquiry(92L, 404L, "pending", null, null, null, "2026-07-13T12:00:00+09:00");

		List<AdminInquiryItem> items = repository.findAdminItems(InquiryStatus.pending, null, 20);

		assertThat(items).extracting(AdminInquiryItem::inquiryId).contains(92L);
		AdminInquiryItem orphan = items.stream()
			.filter(item -> item.inquiryId().equals(92L))
			.findFirst()
			.orElseThrow();
		assertThat(orphan.userId()).isEqualTo(404L);
		assertThat(orphan.userEmail()).isNull();
	}

	@Test
	void findAdminItemByIdReturnsCanonicalProjection() {
		assertThat(repository.findAdminItemById(91L))
			.isPresent()
			.get()
			.satisfies(item -> {
				assertThat(item.inquiryId()).isEqualTo(91L);
				assertThat(item.userId()).isEqualTo(43L);
				assertThat(item.userEmail()).isEqualTo("answered@example.com");
				assertThat(item.status()).isEqualTo(InquiryStatus.answered);
				assertThat(item.answer()).isEqualTo("답변");
				assertThat(item.answeredBy()).isEqualTo(99L);
				assertThat(item.answeredAt()).isEqualTo(OffsetDateTime.parse("2026-07-13T12:00:00+09:00"));
			});
	}

	@Test
	void findAdminItemByIdKeepsOrphanInquiryAndReturnsEmptyForMissingId() {
		insertInquiry(92L, 404L, "pending", null, null, null, "2026-07-13T12:00:00+09:00");

		assertThat(repository.findAdminItemById(92L))
			.isPresent()
			.get()
			.satisfies(item -> {
				assertThat(item.userId()).isEqualTo(404L);
				assertThat(item.userEmail()).isNull();
			});
		assertThat(repository.findAdminItemById(404L)).isEqualTo(Optional.empty());
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
				user_id BIGINT PRIMARY KEY,
				email VARCHAR(254) NOT NULL,
				deleted_at TIMESTAMPTZ
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS inquiries (
				inquiry_id  bigserial PRIMARY KEY,
				user_id     bigint         NOT NULL,
				title       varchar(200)   NOT NULL,
				content     text           NOT NULL,
				status      inquiry_status NOT NULL DEFAULT 'pending',
				answer      text,
				answered_by bigint,
				created_at  timestamptz    NOT NULL DEFAULT now(),
				answered_at timestamptz
			)
			""");
	}

	private void insertUser(Long userId, String email) {
		jdbcTemplate.update("INSERT INTO users (user_id, email) VALUES (?, ?)", userId, email);
	}

	private void insertInquiry(
		Long inquiryId,
		Long userId,
		String status,
		String answer,
		Long answeredBy,
		String answeredAt,
		String createdAt
	) {
		jdbcTemplate.update(
			"""
				INSERT INTO inquiries (
					inquiry_id, user_id, title, content, status, answer, answered_by, answered_at, created_at
				)
				VALUES (?, ?, ?, ?, ?::inquiry_status, ?, ?, ?::timestamptz, ?::timestamptz)
				""",
			inquiryId,
			userId,
			"문의 " + inquiryId,
			"내용 " + inquiryId,
			status,
			answer,
			answeredBy,
			answeredAt,
			createdAt
		);
	}
}
