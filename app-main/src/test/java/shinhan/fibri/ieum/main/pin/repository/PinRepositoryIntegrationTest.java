package shinhan.fibri.ieum.main.pin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
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

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class PinRepositoryIntegrationTest {

	private static final UUID QUESTION_IMAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
	private static final UUID MEETING_IMAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
	private static final UUID MEETING_THUMBNAIL_ID = UUID.fromString("00000000-0000-0000-0000-000000000202");

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
	}

	@Autowired
	private PinRepository pinRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUpSchemaAndRows() {
		createSchema();
		jdbcTemplate.update("TRUNCATE TABLE meeting_participants, meeting_schedules, friendships, question_images, questions, meetings, pins RESTART IDENTITY");

		insertPin(7L, "question", 127.0, 37.5);
		jdbcTemplate.update(
			"INSERT INTO questions (pin_id, title, is_resolved) VALUES (1, 'alive question', false)"
		);
		jdbcTemplate.update(
			"INSERT INTO question_images (question_id, file_id, sort_order) VALUES (1, ?::uuid, 0)",
			QUESTION_IMAGE_ID.toString()
		);

		insertPin(42L, "meeting", 127.1, 37.6);
		jdbcTemplate.update(
			"""
				INSERT INTO meetings (pin_id, title, image_file_id, thumbnail_file_id, status, deleted_at)
				VALUES (2, 'alive meeting', ?::uuid, ?::uuid, 'open'::meeting_status, NULL)
				""",
			MEETING_IMAGE_ID.toString(),
			MEETING_THUMBNAIL_ID.toString()
		);
		insertSchedule(1L, "2099-07-10T19:00:00+09:00", "2099-07-10T23:59:59+09:00");

		insertPin(8L, "question", 127.2, 37.7);
		jdbcTemplate.update(
			"INSERT INTO questions (pin_id, title, is_resolved) VALUES (3, 'resolved question', true)"
		);

		insertPin(99L, "meeting", 127.3, 37.8);
		jdbcTemplate.update(
			"INSERT INTO meetings (pin_id, title, status, deleted_at) VALUES (4, 'blocked meeting', 'open'::meeting_status, NULL)"
		);
		insertSchedule(2L, "2099-07-11T19:00:00+09:00", "2099-07-11T23:59:59+09:00");
		jdbcTemplate.update(
			"INSERT INTO friendships (requester_id, addressee_id, status) VALUES (42, 99, 'blocked')"
		);

		insertPin(10L, "meeting", 129.0, 35.0);
		jdbcTemplate.update(
			"INSERT INTO meetings (pin_id, title, status, deleted_at) VALUES (5, 'outside meeting', 'open'::meeting_status, NULL)"
		);
		insertSchedule(3L, "2099-07-12T19:00:00+09:00", "2099-07-12T23:59:59+09:00");

		insertPin(77L, "meeting", 127.4, 37.9);
		jdbcTemplate.update(
			"INSERT INTO meetings (pin_id, title, status, deleted_at) VALUES (6, 'kicked meeting', 'open'::meeting_status, NULL)"
		);
		insertSchedule(4L, "2099-07-13T19:00:00+09:00", "2099-07-13T23:59:59+09:00");
		jdbcTemplate.update(
			"INSERT INTO meeting_participants (meeting_id, user_id, status, joined_at) VALUES (4, 42, 'kicked'::participant_status, now())"
		);

		insertPin(88L, "meeting", 127.5, 37.95);
		jdbcTemplate.update(
			"INSERT INTO meetings (pin_id, title, status, deleted_at) VALUES (7, 'outdated meeting', 'open'::meeting_status, NULL)"
		);
		insertSchedule(5L, "2000-07-01T19:00:00+09:00", "2000-07-01T23:59:59+09:00");

		insertPin(66L, "meeting", 127.55, 37.96);
		jdbcTemplate.update(
			"INSERT INTO meetings (pin_id, title, status, deleted_at) VALUES (8, 'unscheduled meeting', 'open'::meeting_status, NULL)"
		);

		insertPin(55L, "meeting", 127.6, 37.97);
		jdbcTemplate.update(
			"INSERT INTO meetings (pin_id, title, status, deleted_at) VALUES (9, 'closed meeting', 'closed'::meeting_status, NULL)"
		);
		insertSchedule(7L, "2099-07-14T19:00:00+09:00", "2099-07-14T23:59:59+09:00");

		insertPin(44L, "meeting", 127.7, 37.98);
		jdbcTemplate.update(
			"INSERT INTO meetings (pin_id, title, status, deleted_at) VALUES (10, 'deleted meeting', 'open'::meeting_status, now())"
		);
		insertSchedule(8L, "2099-07-15T19:00:00+09:00", "2099-07-15T23:59:59+09:00");

		insertPin(33L, "question", 127.15, 37.65);
		jdbcTemplate.update(
			"INSERT INTO questions (pin_id, title, is_resolved, deleted_at) VALUES (11, 'deleted question', false, now())"
		);
	}

	@Test
	void findMapPinsExecutesNativePostgisQueryAndMapsProjectionFields() {
		List<PinProjection> rows = pinRepository.findMapPins(
			42L,
			null,
			37.0,
			126.0,
			38.0,
			128.0,
			501
		);

		assertThat(rows).hasSize(5);
		PinProjection meeting = rows.stream()
			.filter(row -> row.getPinId().equals(2L))
			.findFirst()
			.orElseThrow();
		assertThat(meeting.getPinId()).isEqualTo(2L);
		assertThat(meeting.getPinType()).isEqualTo("meeting");
		assertThat(meeting.getTitle()).isEqualTo("alive meeting");
		assertThat(meeting.getThumbnailFileId()).isEqualTo(MEETING_THUMBNAIL_ID);
		assertThat(meeting.getLatitude()).isEqualTo(37.6);
		assertThat(meeting.getLongitude()).isEqualTo(127.1);
		assertThat(meeting.getMine()).isTrue();
		assertThat(meeting.getResolved()).isFalse();
		assertThat(meeting.getCreatedAt()).isBeforeOrEqualTo(Instant.now());

		PinProjection question = rows.stream()
			.filter(row -> row.getPinId().equals(1L))
			.findFirst()
			.orElseThrow();
		assertThat(question.getPinId()).isEqualTo(1L);
		assertThat(question.getPinType()).isEqualTo("question");
		assertThat(question.getTitle()).isEqualTo("alive question");
		assertThat(question.getThumbnailFileId()).isEqualTo(QUESTION_IMAGE_ID);
		assertThat(question.getMine()).isFalse();
		assertThat(question.getResolved()).isFalse();
	}

	@Test
	void findMapPinsIncludesResolvedQuestionsWithResolvedFlagAndExcludesSoftDeleted() {
		List<PinProjection> rows = pinRepository.findMapPins(
			42L,
			"question",
			37.0,
			126.0,
			38.0,
			128.0,
			501
		);

		assertThat(rows).extracting(PinProjection::getTitle)
			.contains("alive question", "resolved question")
			.doesNotContain("deleted question");

		PinProjection resolved = rows.stream()
			.filter(row -> row.getTitle().equals("resolved question"))
			.findFirst()
			.orElseThrow();
		assertThat(resolved.getResolved()).isTrue();

		PinProjection alive = rows.stream()
			.filter(row -> row.getTitle().equals("alive question"))
			.findFirst()
			.orElseThrow();
		assertThat(alive.getResolved()).isFalse();
	}

	@Test
	void findListPinsIncludesResolvedQuestionsWithResolvedFlagAndExcludesSoftDeleted() {
		List<PinProjection> rows = pinRepository.findListPins(42L, "question", null, 50);

		assertThat(rows).extracting(PinProjection::getTitle)
			.contains("alive question", "resolved question")
			.doesNotContain("deleted question");

		PinProjection resolved = rows.stream()
			.filter(row -> row.getTitle().equals("resolved question"))
			.findFirst()
			.orElseThrow();
		assertThat(resolved.getResolved()).isTrue();
	}

	@Test
	void findMapPinsIncludesOpenMeetingsWithoutActiveSchedules() {
		List<PinProjection> rows = pinRepository.findMapPins(
			42L,
			null,
			37.0,
			126.0,
			38.0,
			128.0,
			501
		);

		assertThat(rows)
			.extracting(PinProjection::getTitle)
			.contains("unscheduled meeting", "outdated meeting");
	}

	@Test
	void findListPinsExecutesNullTypeAndCursorFilters() {
		List<PinProjection> firstPage = pinRepository.findListPins(42L, null, null, 3);

		assertThat(firstPage).extracting(PinProjection::getPinId)
			.containsExactly(8L, 7L, 5L);

		List<PinProjection> afterCursor = pinRepository.findListPins(42L, null, 2L, 3);

		assertThat(afterCursor).extracting(PinProjection::getPinId)
			.containsExactly(1L);
	}

	@Test
	void findListPinsExecutesTypeFilter() {
		List<PinProjection> rows = pinRepository.findListPins(42L, "meeting", null, 10);

		assertThat(rows).hasSize(4);
		assertThat(rows).extracting(PinProjection::getPinId)
			.containsExactly(8L, 7L, 5L, 2L);
		assertThat(rows).allSatisfy(row -> assertThat(row.getPinType()).isEqualTo("meeting"));
	}

	@Test
	void findMapPinsKeepClosedDeletedKickedAndBlockedMeetingsHidden() {
		List<PinProjection> rows = pinRepository.findMapPins(
			42L,
			"meeting",
			37.0,
			126.0,
			38.0,
			128.0,
			501
		);

		assertThat(rows).extracting(PinProjection::getTitle)
			.containsExactly("unscheduled meeting", "outdated meeting", "alive meeting")
			.doesNotContain("closed meeting", "deleted meeting", "kicked meeting", "blocked meeting");
	}

	@Test
	void findListPinsIncludesOpenMeetingsWithoutActiveSchedules() {
		List<PinProjection> rows = pinRepository.findListPins(42L, "meeting", null, 10);

		assertThat(rows).extracting(PinProjection::getTitle)
			.contains("unscheduled meeting", "outdated meeting")
			.doesNotContain("closed meeting", "deleted meeting", "kicked meeting", "blocked meeting");
	}

	private void createSchema() {
		jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS postgis");
		jdbcTemplate.execute("""
			DO $$
			BEGIN
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'pin_type') THEN
					CREATE TYPE pin_type AS ENUM ('question', 'meeting');
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'meeting_status') THEN
					CREATE TYPE meeting_status AS ENUM ('open', 'closed', 'cancelled');
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'meeting_schedule_status') THEN
					CREATE TYPE meeting_schedule_status AS ENUM ('scheduled', 'completed', 'cancelled');
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'participant_status') THEN
					CREATE TYPE participant_status AS ENUM ('joined', 'left', 'kicked');
				END IF;
			END
			$$
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS pins (
				pin_id BIGSERIAL PRIMARY KEY,
				author_id BIGINT NOT NULL,
				pin_type pin_type NOT NULL,
				location geography(Point,4326) NOT NULL,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				deleted_at TIMESTAMPTZ
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS questions (
				question_id BIGSERIAL PRIMARY KEY,
				pin_id BIGINT UNIQUE,
				title VARCHAR(200),
				is_resolved BOOLEAN NOT NULL DEFAULT false,
				deleted_at TIMESTAMPTZ
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS meetings (
				meeting_id BIGSERIAL PRIMARY KEY,
				pin_id BIGINT UNIQUE,
				title VARCHAR(200),
				image_file_id UUID,
				thumbnail_file_id UUID,
				status meeting_status NOT NULL DEFAULT 'open',
				deleted_at TIMESTAMPTZ
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS meeting_schedules (
				schedule_id BIGSERIAL PRIMARY KEY,
				meeting_id BIGINT NOT NULL,
				starts_on DATE NOT NULL,
				start_time TIME,
				end_time TIME,
				starts_at TIMESTAMPTZ NOT NULL,
				visible_until TIMESTAMPTZ NOT NULL,
				status meeting_schedule_status NOT NULL,
				deleted_at TIMESTAMPTZ
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS meeting_participants (
				meeting_id BIGINT NOT NULL,
				user_id BIGINT NOT NULL,
				status participant_status NOT NULL,
				joined_at TIMESTAMPTZ NOT NULL,
				PRIMARY KEY (meeting_id, user_id)
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS question_images (
				question_image_id BIGSERIAL PRIMARY KEY,
				question_id BIGINT NOT NULL,
				file_id UUID NOT NULL,
				sort_order INTEGER NOT NULL
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS friendships (
				friendship_id BIGSERIAL PRIMARY KEY,
				requester_id BIGINT NOT NULL,
				addressee_id BIGINT NOT NULL,
				status VARCHAR(30) NOT NULL
			)
			""");
	}

	private void insertPin(Long authorId, String pinType, double longitude, double latitude) {
		jdbcTemplate.update(
			"""
				INSERT INTO pins (author_id, pin_type, location)
				VALUES (?, ?::pin_type, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)
				""",
			authorId,
			pinType,
			longitude,
			latitude
		);
	}

	private void insertSchedule(Long meetingId, String startsAt, String visibleUntil) {
		jdbcTemplate.update(
			"""
				INSERT INTO meeting_schedules (meeting_id, starts_on, start_time, starts_at, visible_until, status)
				VALUES (
				    ?,
				    (?::timestamptz AT TIME ZONE 'Asia/Seoul')::date,
				    (?::timestamptz AT TIME ZONE 'Asia/Seoul')::time,
				    ?::timestamptz,
				    ?::timestamptz,
				    'scheduled'::meeting_schedule_status
				)
				""",
			meetingId,
			startsAt,
			startsAt,
			startsAt,
			visibleUntil
		);
	}
}
