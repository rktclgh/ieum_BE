package shinhan.fibri.ieum.main.admin.user.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;
import shinhan.fibri.ieum.main.admin.user.domain.UserSanction;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

/**
 * 기준 DDL과 같은 PostgreSQL enum/컬럼명으로 제재 만료 쿼리를 검증한다.
 * 운영 DB는 {@code sanction_type}, {@code admin_id}를 사용하므로 구형 {@code type}, {@code created_by}
 * 매핑이 남으면 스케줄러가 매분 SQL 오류를 낸다.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserSanctionRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_main_user_sanction_repository";

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
	}

	@Autowired
	private UserSanctionRepository userSanctionRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUpSchemaAndRows() {
		jdbcTemplate.update("DELETE FROM user_sanctions");
		jdbcTemplate.update("DELETE FROM users");
		insertUser(1L, "suspended");
		insertUser(2L, "suspended");
		insertUser(3L, "suspended");
		insertUser(4L, "suspended");
		insertUser(5L, "active");
		insertUser(99L, "active");

		// user 1: 만료된 활성 temporary — 대상
		insertSanction(1L, "temporary", "2026-07-10T09:00:00+09:00", null, 99L);
		// user 2: 아직 만료되지 않은 temporary — 제외
		insertSanction(2L, "temporary", "2099-01-01T00:00:00+09:00", null, 99L);
		// user 3: permanent(ends_at 없음) — 제외
		insertSanction(3L, "permanent", null, null, 99L);
		// user 4: 이미 해제된 만료 temporary — 제외
		insertSanction(4L, "temporary", "2026-07-10T08:00:00+09:00", "2026-07-10T08:30:00+09:00", 99L);
		// user 5: 이미 active로 복구된 사용자의 만료 이력 — 반복 처리 제외
		insertSanction(5L, "temporary", "2026-07-10T08:00:00+09:00", null, 99L);
	}

	@Test
	void findExpiredTemporarySanctionsUsesCurrentSchemaAndSucceeds() {
		List<ExpiredSanctionRef> expired = userSanctionRepository.findExpiredTemporarySanctions(
			OffsetDateTime.parse("2026-07-10T10:00:00+09:00")
		);

		assertThat(expired).hasSize(1);
		assertThat(expired.get(0).userId()).isEqualTo(1L);
	}

	@Test
	void findExpiredTemporarySanctionsReturnsEmptyWhenNoneExpired() {
		List<ExpiredSanctionRef> expired = userSanctionRepository.findExpiredTemporarySanctions(
			OffsetDateTime.parse("2020-01-01T00:00:00+09:00")
		);

		assertThat(expired).isEmpty();
	}

	@Test
	void saveTemporarySanctionPersistsLedgerColumnsAgainstCurrentSchema() {
		OffsetDateTime endsAt = OffsetDateTime.now().plusMinutes(45);

		UserSanction saved = userSanctionRepository.saveAndFlush(UserSanction.temporary(1L, "manual", 99L, endsAt));

		Map<String, Object> row = jdbcTemplate.queryForMap(
			"""
				SELECT decision_source::text, review_status::text, starts_at, ends_at, duration_minutes,
				       revoked_at, revoked_by, released_at, released_by
				FROM user_sanctions
				WHERE sanction_id = ?
				""",
			saved.getId()
		);
		assertThat(row.get("decision_source")).isEqualTo("admin");
		assertThat(row.get("review_status")).isEqualTo("not_required");
		assertThat(row.get("starts_at")).isNotNull();
		assertThat(row.get("ends_at")).isNotNull();
		assertThat((Integer) row.get("duration_minutes")).isPositive();
		assertThat(row.get("revoked_at")).isNull();
		assertThat(row.get("revoked_by")).isNull();
		assertThat(row.get("released_at")).isNull();
		assertThat(row.get("released_by")).isNull();
	}

	@Test
	void findEffectiveSanctionsForUpdateReturnsOnlyUnrevokedPermanentOrFutureTemporary() {
		OffsetDateTime now = OffsetDateTime.parse("2026-07-10T10:00:00+09:00");

		assertThat(userSanctionRepository.findEffectiveSanctionsForUpdate(1L, now)).isEmpty();
		assertThat(userSanctionRepository.findEffectiveSanctionsForUpdate(2L, now))
			.extracting(UserSanction::getType)
			.containsExactly(SanctionType.temporary);
		assertThat(userSanctionRepository.findEffectiveSanctionsForUpdate(3L, now))
			.extracting(UserSanction::getType)
			.containsExactly(SanctionType.permanent);
		assertThat(userSanctionRepository.findEffectiveSanctionsForUpdate(4L, now)).isEmpty();
	}

	@Test
	void effectiveSanctionExistsAndMaxEndsAtUseCurrentLedgerColumns() {
		OffsetDateTime now = OffsetDateTime.parse("2026-07-10T10:00:00+09:00");

		assertThat(userSanctionRepository.existsEffectiveSanction(1L, now)).isFalse();
		assertThat(userSanctionRepository.existsEffectiveSanction(2L, now)).isTrue();
		assertThat(userSanctionRepository.existsEffectiveSanction(3L, now)).isTrue();
		assertThat(userSanctionRepository.findMaxEffectiveTemporaryEndsAt(2L, now).orElseThrow().toInstant())
			.isEqualTo(OffsetDateTime.parse("2099-01-01T00:00:00+09:00").toInstant());
		assertThat(userSanctionRepository.findMaxEffectiveTemporaryEndsAt(3L, now)).isEmpty();
	}

	@Test
	void releaseUpdatesBothLegacyReleaseAndRevocationColumns() {
		UserSanction sanction = userSanctionRepository.findEffectiveSanctionsForUpdate(
			2L,
			OffsetDateTime.parse("2026-07-10T10:00:00+09:00")
		).getFirst();
		OffsetDateTime releasedAt = OffsetDateTime.parse("2026-07-10T11:00:00+09:00");

		sanction.release(releasedAt, 99L);
		userSanctionRepository.flush();

		Map<String, Object> row = jdbcTemplate.queryForMap(
			"SELECT released_at, released_by, revoked_at, revoked_by FROM user_sanctions WHERE sanction_id = ?",
			sanction.getId()
		);
		assertThat(row.get("released_at")).isNotNull();
		assertThat(row.get("released_by")).isEqualTo(99L);
		assertThat(row.get("revoked_at")).isNotNull();
		assertThat(row.get("revoked_by")).isEqualTo(99L);
	}

	private void insertUser(Long userId, String status) {
		jdbcTemplate.update(
			"""
				INSERT INTO users (user_id, email, password_hash, nickname, email_verified, status)
				VALUES (?, ?, 'hash', ?, true, CAST(? AS user_status))
				""",
			userId,
			"user" + userId + "@example.com",
			"user" + userId,
			status
		);
	}

	private void insertSanction(Long userId, String type, String endsAt, String releasedAt, Long createdBy) {
		jdbcTemplate.update(
			"""
				INSERT INTO user_sanctions (
					user_id, sanction_type, reason, starts_at, ends_at, duration_minutes, admin_id,
					revoked_at, revoked_by, released_at, released_by
				)
				VALUES (
					?, CAST(? AS sanction_type), 'abuse', '2026-07-10T00:00:00+09:00'::timestamptz,
					?::timestamptz,
					CASE WHEN ? = 'temporary' THEN 60 ELSE NULL END,
					?, ?::timestamptz, CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE ? END,
					?::timestamptz, CASE WHEN ?::timestamptz IS NULL THEN NULL ELSE ? END
				)
				""",
			userId,
			type,
			endsAt,
			type,
			createdBy,
			releasedAt,
			releasedAt,
			createdBy,
			releasedAt,
			releasedAt,
			createdBy
		);
	}
}
