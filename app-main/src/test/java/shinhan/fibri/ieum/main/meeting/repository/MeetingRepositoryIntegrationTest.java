package shinhan.fibri.ieum.main.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Optional;
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
class MeetingRepositoryIntegrationTest {

	private static final UUID HOST_PROFILE_FILE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID IMAGE_FILE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID THUMBNAIL_FILE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

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
	private MeetingRepository meetingRepository;

	@Autowired
	private MeetingParticipantRepository participantRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void setUpSchemaAndRows() {
		createSchema();
		jdbcTemplate.update("TRUNCATE TABLE meeting_participants, chat_rooms, meetings, pins, users RESTART IDENTITY");

		jdbcTemplate.update("""
			INSERT INTO users (nickname, profile_file_id, deleted_at)
			VALUES ('오이정', ?::uuid, NULL)
			""", HOST_PROFILE_FILE_ID.toString());
		jdbcTemplate.update("""
			INSERT INTO users (nickname, profile_file_id, deleted_at)
			VALUES ('참여자', NULL, NULL),
			       ('나간사람', NULL, NULL),
			       ('탈퇴자', NULL, now())
			""");
		jdbcTemplate.update("""
			INSERT INTO pins (author_id, pin_type, location, address, detail_address, label, deleted_at)
			VALUES (1, 'meeting'::pin_type, ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography,
			        '서울특별시 강남구 테헤란로 123', '2번 출구 앞', '동선역 2번 출구', NULL)
			""");
		jdbcTemplate.update("""
			INSERT INTO meetings (
				pin_id, host_id, title, content, meeting_at, max_members,
				image_file_id, thumbnail_file_id, status, created_at, updated_at, deleted_at
			)
			VALUES (
				1, 1, '저녁 모임', '같이 밥 먹어요',
				'2026-07-10T19:00:00+09:00', 7,
				?::uuid, ?::uuid, 'open'::meeting_status,
				'2026-07-09T10:00:00+09:00', '2026-07-09T10:00:00+09:00', NULL
			)
			""", IMAGE_FILE_ID.toString(), THUMBNAIL_FILE_ID.toString());
		jdbcTemplate.update("INSERT INTO chat_rooms (room_type, meeting_id) VALUES ('group'::room_type, 1)");
		jdbcTemplate.update("""
			INSERT INTO meeting_participants (meeting_id, user_id, status, joined_at)
			VALUES (1, 2, 'joined'::participant_status, '2026-07-09T11:00:00+09:00'),
			       (1, 1, 'joined'::participant_status, '2026-07-09T10:00:00+09:00'),
			       (1, 3, 'left'::participant_status, '2026-07-09T09:00:00+09:00'),
			       (1, 4, 'joined'::participant_status, '2026-07-09T08:00:00+09:00')
			""");
	}

	@Test
	void findDetailByIdMapsMeetingHostRoomAndLocationFields() {
		MeetingDetailProjection detail = meetingRepository.findDetailById(1L).orElseThrow();

		assertThat(detail.getMeetingId()).isEqualTo(1L);
		assertThat(detail.getPinId()).isEqualTo(1L);
		assertThat(detail.getRoomId()).isEqualTo(1L);
		assertThat(detail.getTitle()).isEqualTo("저녁 모임");
		assertThat(detail.getContent()).isEqualTo("같이 밥 먹어요");
		assertThat(detail.getAddress()).isEqualTo("서울특별시 강남구 테헤란로 123");
		assertThat(detail.getDetailAddress()).isEqualTo("2번 출구 앞");
		assertThat(detail.getLabel()).isEqualTo("동선역 2번 출구");
		assertThat(detail.getMeetingAt()).isEqualTo(OffsetDateTime.parse("2026-07-10T19:00:00+09:00").toInstant());
		assertThat(detail.getStatus()).isEqualTo("open");
		assertThat(detail.getMaxMembers()).isEqualTo(7);
		assertThat(detail.getHostUserId()).isEqualTo(1L);
		assertThat(detail.getHostNickname()).isEqualTo("오이정");
		assertThat(detail.getHostProfileFileId()).isEqualTo(HOST_PROFILE_FILE_ID);
		assertThat(detail.getImageFileId()).isEqualTo(IMAGE_FILE_ID);
		assertThat(detail.getThumbnailFileId()).isEqualTo(THUMBNAIL_FILE_ID);
		assertThat(detail.getLatitude()).isEqualTo(37.5);
		assertThat(detail.getLongitude()).isEqualTo(127.0);
		assertThat(detail.getCreatedAt()).isEqualTo(OffsetDateTime.parse("2026-07-09T10:00:00+09:00").toInstant());
	}

	@Test
	void findDetailByIdHidesDeletedMeeting() {
		jdbcTemplate.update("UPDATE meetings SET deleted_at = now() WHERE meeting_id = 1");

		Optional<MeetingDetailProjection> detail = meetingRepository.findDetailById(1L);

		assertThat(detail).isEmpty();
	}

	@Test
	void findGroupRoomIdByMeetingIdReturnsGroupRoomId() {
		Optional<Long> roomId = meetingRepository.findGroupRoomIdByMeetingId(1L);

		assertThat(roomId).contains(1L);
	}

	@Test
	void findJoinedParticipantsByMeetingIdReturnsOnlyActiveJoinedUsersInJoinOrder() {
		var rows = participantRepository.findJoinedParticipantsByMeetingId(1L);

		assertThat(rows).hasSize(2);
		assertThat(rows).extracting(MeetingParticipantProjection::getUserId)
			.containsExactly(1L, 2L);
		assertThat(rows.get(0).getNickname()).isEqualTo("오이정");
		assertThat(rows.get(0).getProfileFileId()).isEqualTo(HOST_PROFILE_FILE_ID);
		assertThat(rows.get(0).getJoinedAt()).isEqualTo(OffsetDateTime.parse("2026-07-09T10:00:00+09:00").toInstant());
		assertThat(rows.get(1).getNickname()).isEqualTo("참여자");
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
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'meeting_type') THEN
					CREATE TYPE meeting_type AS ENUM ('one_time', 'recurring');
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'room_type') THEN
					CREATE TYPE room_type AS ENUM ('direct', 'group', 'question');
				END IF;
				IF NOT EXISTS (SELECT 1 FROM pg_type WHERE typname = 'participant_status') THEN
					CREATE TYPE participant_status AS ENUM ('joined', 'left', 'kicked');
				END IF;
			END
			$$
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS users (
				user_id BIGSERIAL PRIMARY KEY,
				nickname VARCHAR(50),
				profile_file_id UUID,
				deleted_at TIMESTAMPTZ
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS pins (
				pin_id BIGSERIAL PRIMARY KEY,
				author_id BIGINT NOT NULL,
				pin_type pin_type NOT NULL,
				location geography(Point,4326) NOT NULL,
				address VARCHAR(255) NOT NULL,
				detail_address VARCHAR(200) NOT NULL DEFAULT '',
				label VARCHAR(100) NOT NULL DEFAULT '',
				deleted_at TIMESTAMPTZ
			)
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS meetings (
				meeting_id BIGSERIAL PRIMARY KEY,
				pin_id BIGINT NOT NULL,
				host_id BIGINT NOT NULL,
				title VARCHAR(200) NOT NULL,
				content TEXT,
				type meeting_type NOT NULL DEFAULT 'one_time',
				meeting_at TIMESTAMPTZ NOT NULL,
				max_members SMALLINT NOT NULL,
				image_file_id UUID,
				thumbnail_file_id UUID,
				status meeting_status NOT NULL,
				created_at TIMESTAMPTZ NOT NULL,
				updated_at TIMESTAMPTZ NOT NULL,
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
			CREATE TABLE IF NOT EXISTS chat_rooms (
				room_id BIGSERIAL PRIMARY KEY,
				room_type room_type NOT NULL,
				meeting_id BIGINT UNIQUE
			)
			""");
	}
}
