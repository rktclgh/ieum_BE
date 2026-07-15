package shinhan.fibri.ieum.main.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
class MeetingCalendarRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_main_meeting_calendar";
	private static final OffsetDateTime FROM = OffsetDateTime.parse("2099-07-01T00:00:00+09:00");
	private static final OffsetDateTime TO = OffsetDateTime.parse("2099-08-01T00:00:00+09:00");

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
	}

	@Autowired
	private MeetingScheduleRepository repository;

	@Autowired
	private JdbcClient jdbc;

	private long hostId;
	private long memberId;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE TABLE users RESTART IDENTITY CASCADE").update();
		hostId = insertUser("host");
		memberId = insertUser("member");
	}

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void returnsUnscheduledBeforeDatedItemsForJoinedMeetings() {
		long unscheduledMeetingId = insertMeeting("unscheduled", "joined", false);
		long scheduledMeetingId = insertMeeting("scheduled", "joined", false);
		long scheduleId = insertSchedule(
			scheduledMeetingId,
			memberId,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00")
		);

		List<MeetingCalendarProjection> rows = repository.findCalendarItems(memberId, FROM, TO, 1000);

		assertThat(rows).hasSize(2);
		MeetingCalendarProjection unscheduled = rows.getFirst();
		assertThat(unscheduled.getMeetingId()).isEqualTo(unscheduledMeetingId);
		assertThat(unscheduled.getScheduleId()).isNull();
		assertThat(unscheduled.getStartsAt()).isNull();
		assertThat(unscheduled.getEndsAt()).isNull();
		assertThat(unscheduled.getCreatedByUserId()).isNull();
		assertThat(unscheduled.getStatus()).isEqualTo("unscheduled");

		MeetingCalendarProjection scheduled = rows.get(1);
		assertThat(scheduled.getMeetingId()).isEqualTo(scheduledMeetingId);
		assertThat(scheduled.getScheduleId()).isEqualTo(scheduleId);
		assertThat(scheduled.getCreatedByUserId()).isEqualTo(memberId);
		assertThat(scheduled.getStatus()).isEqualTo("scheduled");
	}

	@Test
	void doesNotSynthesizePlaceholderWhenScheduledRowExistsOutsideRange() {
		long meetingId = insertMeeting("outside-range", "joined", false);
		insertSchedule(meetingId, memberId, OffsetDateTime.parse("2100-07-10T19:00:00+09:00"));

		List<MeetingCalendarProjection> rows = repository.findCalendarItems(memberId, FROM, TO, 1000);

		assertThat(rows).isEmpty();
	}

	@Test
	void doesNotSynthesizePlaceholderForRecurringMeetingWithoutSchedule() {
		insertMeeting("recurring", "joined", false, "recurring");

		List<MeetingCalendarProjection> rows = repository.findCalendarItems(memberId, FROM, TO, 1000);

		assertThat(rows).isEmpty();
	}

	@Test
	void doesNotSynthesizePlaceholderWhenCompletedScheduleExists() {
		long meetingId = insertMeeting("completed", "joined", false);
		long scheduleId = insertSchedule(
			meetingId,
			memberId,
			OffsetDateTime.parse("2099-06-10T19:00:00+09:00")
		);
		jdbc.sql("UPDATE meeting_schedules SET status = 'completed' WHERE schedule_id = :scheduleId")
			.param("scheduleId", scheduleId)
			.update();

		List<MeetingCalendarProjection> rows = repository.findCalendarItems(memberId, FROM, TO, 1000);

		assertThat(rows).isEmpty();
	}

	@Test
	void synthesizesPlaceholderWhenOnlyCancelledSchedulesRemain() {
		long meetingId = insertMeeting("cancelled", "joined", false);
		long scheduleId = insertSchedule(
			meetingId,
			memberId,
			OffsetDateTime.parse("2099-07-10T19:00:00+09:00")
		);
		jdbc.sql("UPDATE meeting_schedules SET status = 'cancelled' WHERE schedule_id = :scheduleId")
			.param("scheduleId", scheduleId)
			.update();

		List<MeetingCalendarProjection> rows = repository.findCalendarItems(memberId, FROM, TO, 1000);

		assertThat(rows).singleElement().satisfies(row -> {
			assertThat(row.getMeetingId()).isEqualTo(meetingId);
			assertThat(row.getStatus()).isEqualTo("unscheduled");
			assertThat(row.getScheduleId()).isNull();
		});
	}

	@Test
	void excludesUnscheduledMeetingsForLeftKickedAndDeletedMemberships() {
		insertMeeting("left", "left", false);
		insertMeeting("kicked", "kicked", false);
		insertMeeting("deleted", "joined", true);

		List<MeetingCalendarProjection> rows = repository.findCalendarItems(memberId, FROM, TO, 1000);

		assertThat(rows).isEmpty();
	}

	private long insertUser(String nickname) {
		return jdbc.sql("""
			INSERT INTO users (email, provider, password_hash, nickname, email_verified, role, status)
			VALUES (:email, 'email', 'hash', :nickname, true, 'user', 'active')
			RETURNING user_id
			""")
			.param("email", nickname + "@example.com")
			.param("nickname", nickname)
			.query(Long.class)
			.single();
	}

	private long insertMeeting(String title, String participantStatus, boolean deleted) {
		return insertMeeting(title, participantStatus, deleted, "one_time");
	}

	private long insertMeeting(String title, String participantStatus, boolean deleted, String type) {
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address, label)
			VALUES (
				:hostId,
				'meeting',
				ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography,
				'Seoul',
				:title
			)
			RETURNING pin_id
			""")
			.param("hostId", hostId)
			.param("title", title)
			.query(Long.class)
			.single();
		long meetingId = jdbc.sql("""
			INSERT INTO meetings (
				pin_id, host_id, title, type, meeting_at, max_members, status, deleted_at
			)
			VALUES (
				:pinId, :hostId, :title, CAST(:type AS meeting_type), NULL, 10, 'open',
				CASE WHEN :deleted THEN CURRENT_TIMESTAMP ELSE NULL END
			)
			RETURNING meeting_id
			""")
			.param("pinId", pinId)
			.param("hostId", hostId)
			.param("title", title)
			.param("type", type)
			.param("deleted", deleted)
			.query(Long.class)
			.single();
		jdbc.sql("""
			INSERT INTO meeting_participants (meeting_id, user_id, status)
			VALUES (:meetingId, :memberId, CAST(:status AS participant_status))
			""")
			.param("meetingId", meetingId)
			.param("memberId", memberId)
			.param("status", participantStatus)
			.update();
		jdbc.sql("""
			INSERT INTO chat_rooms (room_type, meeting_id)
			VALUES ('group', :meetingId)
			""")
			.param("meetingId", meetingId)
			.update();
		return meetingId;
	}

	private long insertSchedule(long meetingId, long createdBy, OffsetDateTime startsAt) {
		return jdbc.sql("""
			INSERT INTO meeting_schedules (
				meeting_id, created_by, starts_at, visible_until, status, sequence_no
			)
			VALUES (
				:meetingId, :createdBy, :startsAt, :visibleUntil, 'scheduled', 1
			)
			RETURNING schedule_id
			""")
			.param("meetingId", meetingId)
			.param("createdBy", createdBy)
			.param("startsAt", startsAt)
			.param("visibleUntil", startsAt.withHour(23).withMinute(59).withSecond(59))
			.query(Long.class)
			.single();
	}
}
