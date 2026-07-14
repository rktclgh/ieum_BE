package shinhan.fibri.ieum.main.friend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import shinhan.fibri.ieum.common.friend.repository.FriendshipRepository;

/**
 * SnakeCasePostgreSQLDialect 회귀 테스트 — 실제 Postgres의 native {@code friendship_status} enum 대상.
 *
 * <p>enum 리터럴이 박힌 JPQL({@code existsAcceptedByUserPair} 등)이
 * {@code 'accepted'::friendship_status} 로 렌더돼 42704 없이 동작하는지 검증한다.
 * 기본 PostgreSQLDialect였다면 {@code ::friendshipstatus} 로 캐스팅해 실패한다(운영에서 발생한 그 버그).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class FriendshipRepositoryIntegrationTest {

	@Container
	@SuppressWarnings("resource")
	static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
		DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres")
	);

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
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
	private FriendshipRepository friendshipRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUpSchema() {
		createSchema();
		jdbcTemplate.update("TRUNCATE TABLE friendships RESTART IDENTITY");
		insertFriendship(1L, 2L, "accepted", null);
		insertFriendship(3L, 4L, "blocked", 3L);
	}

	@Test
	void existsAcceptedByUserPairRendersSnakeCaseEnumCastAndSucceeds() {
		assertThat(friendshipRepository.existsAcceptedByUserPair(1L, 2L)).isTrue();
		assertThat(friendshipRepository.existsAcceptedByUserPair(2L, 1L)).isTrue();
		assertThat(friendshipRepository.existsAcceptedByUserPair(1L, 3L)).isFalse();
	}

	@Test
	void existsBlockedByUserPairRendersSnakeCaseEnumCastAndSucceeds() {
		assertThat(friendshipRepository.existsBlockedByUserPair(3L, 4L)).isTrue();
		assertThat(friendshipRepository.existsBlockedByUserPair(1L, 2L)).isFalse();
	}

	private void createSchema() {
		jdbcTemplate.execute("""
			DO $$
			BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'friendship_status') THEN
					CREATE TYPE friendship_status AS ENUM ('pending', 'accepted', 'blocked');
				END IF;
			END
			$$
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS friendships (
				friendship_id BIGSERIAL PRIMARY KEY,
				requester_id BIGINT NOT NULL,
				addressee_id BIGINT NOT NULL,
				status friendship_status NOT NULL,
				blocked_by BIGINT,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
			)
			""");
	}

	private void insertFriendship(Long requesterId, Long addresseeId, String status, Long blockedBy) {
		jdbcTemplate.update(
			"INSERT INTO friendships (requester_id, addressee_id, status, blocked_by)"
				+ " VALUES (?, ?, ?::friendship_status, ?)",
			requesterId,
			addresseeId,
			status,
			blockedBy
		);
	}
}
