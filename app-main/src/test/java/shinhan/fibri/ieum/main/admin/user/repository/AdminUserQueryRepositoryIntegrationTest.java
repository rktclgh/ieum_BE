package shinhan.fibri.ieum.main.admin.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import shinhan.fibri.ieum.main.admin.user.repository.AdminUserQueryRepository.AdminUserRow;

/**
 * status 컬럼이 실제 Postgres native enum(user_status)인 최악의 경우를 가정한 회귀 테스트.
 * {@code u.status = :status}(varchar 파라미터)로 비교하면 enum = varchar 연산자가 없어 500이 나는데,
 * {@code CAST(u.status AS varchar) = :status}로 바꾼 뒤 실제 native enum 컬럼 대상으로 정상 동작하는지,
 * LIKE 검색어 이스케이프·삭제 회원 제외·커서 페이지네이션이 함께 검증한다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Import(AdminUserQueryRepository.class)
class AdminUserQueryRepositoryIntegrationTest {

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
		DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres")
	);

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		// prepareThreshold=0: pgjdbc의 서버사이드 prepared statement 캐싱을 끈다. 매 테스트마다
		// TRUNCATE로 users 테이블을 재생성하면서 같은 SQL 텍스트를 반복 실행하는데, 캐싱이 켜져 있으면
		// "cached plan must not change result type" 오류가 난다(테스트 하네스 특유의 현상, 실제 쿼리 로직과 무관).
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
	private AdminUserQueryRepository adminUserQueryRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUpSchemaAndRows() {
		createSchema();
		jdbcTemplate.update("TRUNCATE TABLE users RESTART IDENTITY");

		insertUser(1L, "user1@example.com", "alice", "active", null);
		insertUser(2L, "user2@example.com", "bob", "suspended", null);
		insertUser(3L, "special@example.com", "50_off", "active", null);
		insertUser(4L, "deleted@example.com", "carol", "active", "2026-07-01T00:00:00+09:00");
		insertUser(5L, "decoy@example.com", "50xoff", "active", null);
	}

	@Test
	void findUsersFiltersByStatusAgainstNativeEnumColumn() {
		List<AdminUserRow> rows = adminUserQueryRepository.findUsers("suspended", null, null, 10);

		assertThat(rows).extracting(AdminUserRow::userId).containsExactly(2L);
	}

	@Test
	void findUsersExcludesSoftDeletedUsers() {
		List<AdminUserRow> rows = adminUserQueryRepository.findUsers(null, null, null, 10);

		assertThat(rows).extracting(AdminUserRow::userId).doesNotContain(4L);
	}

	@Test
	void findUsersEscapesLikeWildcardsInSearchTerm() {
		// 이스케이프 없이 "50_off"를 그대로 LIKE에 넣으면 '_'가 와일드카드로 동작해
		// "50xoff"(decoy, user 5)도 함께 매치된다. 이스케이프가 되어 있으면 "50_off"만 정확히 매치돼야 한다.
		List<AdminUserRow> rows = adminUserQueryRepository.findUsers(null, "%50\\_off%", null, 10);

		assertThat(rows).extracting(AdminUserRow::nickname).containsExactly("50_off");
	}

	@Test
	void findUsersMatchesEmailWhenNicknameDoesNotContainSearchTerm() {
		List<AdminUserRow> rows = adminUserQueryRepository.findUsers(null, "%user1@%", null, 10);

		assertThat(rows).extracting(AdminUserRow::userId).containsExactly(1L);
	}

	@Test
	void findUsersOrdersByUserIdDescAndAppliesCursor() {
		List<AdminUserRow> firstPage = adminUserQueryRepository.findUsers(null, null, null, 2);
		assertThat(firstPage).extracting(AdminUserRow::userId).containsExactly(5L, 3L);

		List<AdminUserRow> afterCursor = adminUserQueryRepository.findUsers(null, null, 3L, 2);
		assertThat(afterCursor).extracting(AdminUserRow::userId).containsExactly(2L, 1L);
	}

	private void createSchema() {
		jdbcTemplate.execute("""
			DO $$
			BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_status') THEN
					CREATE TYPE user_status AS ENUM ('active', 'suspended');
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_role') THEN
					CREATE TYPE user_role AS ENUM ('user', 'admin');
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'user_grade') THEN
					CREATE TYPE user_grade AS ENUM ('bronze', 'silver', 'gold', 'platinum', 'diamond');
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'auth_provider') THEN
					CREATE TYPE auth_provider AS ENUM ('email', 'google', 'kakao');
				END IF;
			END
			$$
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS users (
				user_id BIGINT PRIMARY KEY,
				email VARCHAR(254) NOT NULL,
				nickname VARCHAR(50) NOT NULL,
				role user_role NOT NULL DEFAULT 'user',
				status user_status NOT NULL,
				grade user_grade NOT NULL DEFAULT 'bronze',
				provider auth_provider NOT NULL DEFAULT 'email',
				last_active_at TIMESTAMPTZ,
				deleted_at TIMESTAMPTZ
			)
			""");
	}

	private void insertUser(Long userId, String email, String nickname, String status, String deletedAt) {
		jdbcTemplate.update(
			"""
				INSERT INTO users (user_id, email, nickname, status, deleted_at)
				VALUES (?, ?, ?, ?::user_status, ?::timestamptz)
				""",
			userId,
			email,
			nickname,
			status,
			deletedAt
		);
	}
}
