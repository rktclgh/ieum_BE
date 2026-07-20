package shinhan.fibri.ieum.main.notification.presence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;

class JdbcRadiusAudienceResolverIntegrationTest {

	private static final String DATABASE = "radius_audience_test";

	private JdbcTemplate jdbcTemplate;
	private JdbcRadiusAudienceResolver resolver;

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		jdbcTemplate = new JdbcTemplate(CanonicalPostgresContainer.dataSource(DATABASE));
		resolver = new JdbcRadiusAudienceResolver(new NamedParameterJdbcTemplate(jdbcTemplate));
		createSchema();
	}

	@Test
	void resolvesNearbyQuestionRecipientsFromLastLocationAndSettings() {
		insertUser(1L, 37.5665, 126.9780, true, true, true, 5, null);
		insertUser(2L, 37.5700, 126.9780, true, true, true, 5, null);
		insertUser(3L, 37.5700, 126.9780, true, false, true, 5, null);
		insertUser(4L, 37.5700, 126.9780, true, true, true, 5, null);
		insertUser(5L, 37.7000, 126.9780, true, true, true, 3, null);
		insertUser(6L, null, null, true, true, true, 5, null);
		insertUser(7L, 37.5700, 126.9780, true, true, true, 5, "2026-07-01T00:00:00+09:00");
		insertUser(8L, 37.5700, 126.9780, true, true, true, 5, null, "suspended");

		List<Long> recipients = resolver.resolve(
			37.5665,
			126.9780,
			NotificationCategory.question,
			1L,
			Set.of(4L)
		);

		assertThat(recipients).containsExactly(2L);
	}

	@Test
	void appliesMeetingFlagAndPerUserRadius() {
		insertUser(1L, 37.5665, 126.9780, true, true, true, 5, null);
		insertUser(2L, 37.5700, 126.9780, true, true, false, 5, null);
		insertUser(3L, 37.6100, 126.9780, true, true, true, 3, null);
		insertUser(4L, 37.6100, 126.9780, true, true, true, 10, null);
		insertUser(5L, 37.5700, 126.9780, false, true, true, 5, null);

		List<Long> recipients = resolver.resolve(
			37.5665,
			126.9780,
			NotificationCategory.meeting,
			1L,
			Set.of()
		);

		assertThat(recipients).containsExactly(4L);
	}

	private void createSchema() {
		jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis");
		jdbcTemplate.execute("CREATE TYPE user_status AS ENUM ('active', 'suspended')");
		jdbcTemplate.execute("""
			CREATE TABLE users (
				user_id BIGINT PRIMARY KEY,
				status user_status NOT NULL DEFAULT 'active',
				last_location GEOGRAPHY(Point, 4326),
				deleted_at TIMESTAMPTZ
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE user_settings (
				user_id BIGINT PRIMARY KEY REFERENCES users(user_id) ON DELETE CASCADE,
				notify_all BOOLEAN NOT NULL DEFAULT TRUE,
				notify_meeting BOOLEAN NOT NULL DEFAULT TRUE,
				notify_question BOOLEAN NOT NULL DEFAULT TRUE,
				notify_radius_km SMALLINT NOT NULL DEFAULT 5 CHECK (notify_radius_km IN (3, 5, 10))
			)
			""");
	}

	private void insertUser(
		Long userId,
		Double latitude,
		Double longitude,
		boolean notifyAll,
		boolean notifyQuestion,
		boolean notifyMeeting,
		int radiusKm,
		String deletedAt
	) {
		insertUser(userId, latitude, longitude, notifyAll, notifyQuestion, notifyMeeting, radiusKm, deletedAt, "active");
	}

	private void insertUser(
		Long userId,
		Double latitude,
		Double longitude,
		boolean notifyAll,
		boolean notifyQuestion,
		boolean notifyMeeting,
		int radiusKm,
		String deletedAt,
		String status
	) {
		jdbcTemplate.update(
			"""
				INSERT INTO users (user_id, status, last_location, deleted_at)
				VALUES (
					?,
					?::user_status,
					CASE WHEN ?::double precision IS NULL OR ?::double precision IS NULL
						THEN NULL
						ELSE ST_SetSRID(ST_MakePoint(?::double precision, ?::double precision), 4326)::geography
					END,
					?::timestamptz
				)
				""",
			userId,
			status,
			longitude,
			latitude,
			longitude,
			latitude,
			deletedAt
		);
		jdbcTemplate.update(
			"""
				INSERT INTO user_settings (user_id, notify_all, notify_question, notify_meeting, notify_radius_km)
				VALUES (?, ?, ?, ?, ?)
				""",
			userId,
			notifyAll,
			notifyQuestion,
			notifyMeeting,
			radiusKm
		);
	}
}
