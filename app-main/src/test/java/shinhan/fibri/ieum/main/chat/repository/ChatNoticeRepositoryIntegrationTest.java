package shinhan.fibri.ieum.main.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.common.chat.domain.ChatNotice;
import shinhan.fibri.ieum.common.chat.repository.ChatNoticeRepository;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers(disabledWithoutDocker = true)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChatNoticeRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_chat_notice_repository";
	private static final long ROOM_ID = 10L;
	private static final long OTHER_ROOM_ID = 20L;
	private static final long CURRENT_USER_ID = 1L;
	private static final long SENDER_ID = 2L;
	private static final long NO_MEMBER_USER_ID = 3L;
	private static final long LEFT_USER_ID = 4L;
	private static final OffsetDateTime BASE_TIME = OffsetDateTime.parse("2026-07-21T12:00:00+09:00");

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
	}

	@Autowired
	private ChatNoticeRepository repository;

	@Autowired
	private JdbcTemplate jdbc;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
		insertUsers();
		insertRooms();
		insertMembers();
		insertMessages();
	}

	@Test
	void findVisibleSourceMessageAllowsOnlyCurrentActiveMemberTextMessagesInTheRoom() {
		assertThat(repository.findVisibleSourceMessage(ROOM_ID, 101L, CURRENT_USER_ID))
			.isPresent()
			.get()
			.satisfies(message -> {
				assertThat(message.getId()).isEqualTo(101L);
				assertThat(message.getRoom().getId()).isEqualTo(ROOM_ID);
				assertThat(message.getSender().getId()).isEqualTo(SENDER_ID);
			});

		assertThat(repository.findVisibleSourceMessage(ROOM_ID, 201L, CURRENT_USER_ID)).isEmpty();
		assertThat(repository.findVisibleSourceMessage(ROOM_ID, 101L, NO_MEMBER_USER_ID)).isEmpty();
		assertThat(repository.findVisibleSourceMessage(ROOM_ID, 101L, LEFT_USER_ID)).isEmpty();
		assertThat(repository.findVisibleSourceMessage(ROOM_ID, 100L, CURRENT_USER_ID)).isEmpty();
		assertThat(repository.findVisibleSourceMessage(ROOM_ID, 102L, CURRENT_USER_ID)).isEmpty();
		assertThat(repository.findVisibleSourceMessage(ROOM_ID, 103L, CURRENT_USER_ID)).isEmpty();
		assertThat(repository.findVisibleSourceMessage(ROOM_ID, 104L, CURRENT_USER_ID)).isEmpty();
	}

	@Test
	void insertIgnoreUsesPostgresOnConflictReturningForCanonicalDuplicateBehavior() {
		assertThat(repository.insertIgnore(ROOM_ID, 101L, CURRENT_USER_ID)).contains(1L);
		assertThat(repository.insertIgnore(ROOM_ID, 101L, SENDER_ID)).isEmpty();

		assertThat(jdbc.queryForObject("""
			SELECT count(*)
			FROM chat_notices
			WHERE room_id = ? AND message_id = ?
			""", Integer.class, ROOM_ID, 101L)).isOne();
		assertThat(jdbc.queryForObject("""
			SELECT created_by
			FROM chat_notices
			WHERE room_id = ? AND message_id = ?
			""", Long.class, ROOM_ID, 101L)).isEqualTo(CURRENT_USER_ID);

		assertThat(repository.findVisibleByRoomIdAndMessageId(ROOM_ID, 101L, CURRENT_USER_ID))
			.isPresent()
			.get()
			.extracting(ChatNotice::getId)
			.isEqualTo(1L);
	}

	@Test
	void visibleNoticeQueriesApplyMembershipFiltersAndCursorOrderIncludingPinnedLookup() {
		insertNotice(1001L, ROOM_ID, 105L, BASE_TIME);
		insertNotice(1002L, ROOM_ID, 106L, BASE_TIME);
		insertNotice(1003L, ROOM_ID, 107L, BASE_TIME.minusHours(1));
		insertNotice(1004L, ROOM_ID, 100L, BASE_TIME.plusHours(1));
		insertNotice(1005L, ROOM_ID, 102L, BASE_TIME.plusHours(2));
		insertNotice(1006L, ROOM_ID, 103L, BASE_TIME.plusHours(3));
		insertNotice(1007L, ROOM_ID, 104L, BASE_TIME.plusHours(4));
		insertNotice(1008L, OTHER_ROOM_ID, 201L, BASE_TIME.plusHours(5));
		pinNotice(ROOM_ID, 1002L);

		assertThat(repository.findLatestVisible(ROOM_ID, CURRENT_USER_ID, PageRequest.of(0, 2)))
			.extracting(ChatNotice::getId)
			.containsExactly(1002L, 1001L);
		assertThat(repository.findVisibleBeforeCursor(
			ROOM_ID,
			CURRENT_USER_ID,
			BASE_TIME,
			1001L,
			PageRequest.of(0, 3)
		))
			.extracting(ChatNotice::getId)
			.containsExactly(1003L);

		assertThat(pinnedNoticeId(ROOM_ID)).isEqualTo(1002L);
		assertThat(repository.findVisibleByIdAndRoomId(1002L, ROOM_ID, CURRENT_USER_ID))
			.isPresent()
			.get()
			.satisfies(notice -> {
				assertThat(notice.getId()).isEqualTo(1002L);
				assertThat(notice.getMessage().getId()).isEqualTo(106L);
				assertThat(notice.getMessage().getSender().getId()).isEqualTo(SENDER_ID);
			});
		assertThat(repository.findVisibleByRoomIdAndMessageId(ROOM_ID, 106L, CURRENT_USER_ID))
			.isPresent()
			.get()
			.extracting(ChatNotice::getId)
			.isEqualTo(1002L);

		assertThat(repository.findVisibleByIdAndRoomId(1002L, ROOM_ID, LEFT_USER_ID)).isEmpty();
		assertThat(repository.findVisibleByIdAndRoomId(1004L, ROOM_ID, CURRENT_USER_ID)).isEmpty();
		assertThat(repository.findVisibleByIdAndRoomId(1005L, ROOM_ID, CURRENT_USER_ID)).isEmpty();
		assertThat(repository.findVisibleByIdAndRoomId(1006L, ROOM_ID, CURRENT_USER_ID)).isEmpty();
		assertThat(repository.findVisibleByIdAndRoomId(1007L, ROOM_ID, CURRENT_USER_ID)).isEmpty();
		assertThat(repository.findVisibleByIdAndRoomId(1008L, ROOM_ID, CURRENT_USER_ID)).isEmpty();
	}

	private void insertUsers() {
		List.of(CURRENT_USER_ID, SENDER_ID, NO_MEMBER_USER_ID, LEFT_USER_ID).forEach(userId ->
			jdbc.update("""
				INSERT INTO users (user_id, email, password_hash, nickname, email_verified)
				VALUES (?, ?, 'hash', ?, true)
				""",
				userId,
				"notice-user-%d@example.com".formatted(userId),
				"notice-user-%d".formatted(userId)
			)
		);
	}

	private void insertRooms() {
		jdbc.update("""
			INSERT INTO chat_rooms (room_id, room_type, room_key)
			VALUES (?, 'direct', ?), (?, 'direct', ?)
			""",
			ROOM_ID,
			"d:%d:%d".formatted(CURRENT_USER_ID, SENDER_ID),
			OTHER_ROOM_ID,
			"d:%d:%d".formatted(CURRENT_USER_ID, NO_MEMBER_USER_ID)
		);
	}

	private void insertMembers() {
		jdbc.update("""
			INSERT INTO chat_members (room_id, user_id, visible_after_message_id)
			VALUES (?, ?, 100), (?, ?, 0), (?, ?, 0)
			""",
			ROOM_ID,
			CURRENT_USER_ID,
			ROOM_ID,
			SENDER_ID,
			OTHER_ROOM_ID,
			CURRENT_USER_ID
		);
		jdbc.update("""
			INSERT INTO chat_members (room_id, user_id, left_at, visible_after_message_id)
			VALUES (?, ?, ?, 0)
			""", ROOM_ID, LEFT_USER_ID, BASE_TIME);
	}

	private void insertMessages() {
		insertTextMessage(100L, ROOM_ID, "cutoff-message", "user", null);
		insertTextMessage(101L, ROOM_ID, "visible-message", "user", null);
		insertTextMessage(102L, ROOM_ID, "deleted-message", "user", BASE_TIME);
		insertTextMessage(103L, ROOM_ID, "system-message", "system", null);
		jdbc.update("""
			INSERT INTO messages (message_id, room_id, sender_id, image_file_id, message_type, created_at)
			VALUES (?, ?, ?, ?, 'user', ?)
			""", 104L, ROOM_ID, SENDER_ID, UUID.fromString("00000000-0000-0000-0000-000000000104"), BASE_TIME);
		insertTextMessage(105L, ROOM_ID, "notice-one", "user", null);
		insertTextMessage(106L, ROOM_ID, "notice-two", "user", null);
		insertTextMessage(107L, ROOM_ID, "notice-three", "user", null);
		insertTextMessage(201L, OTHER_ROOM_ID, "foreign-room", "user", null);
	}

	private void insertTextMessage(Long messageId, Long roomId, String content, String messageType, OffsetDateTime deletedAt) {
		jdbc.update("""
			INSERT INTO messages (message_id, room_id, sender_id, content, message_type, created_at, deleted_at)
			VALUES (?, ?, ?, ?, ?, ?, ?)
			""", messageId, roomId, SENDER_ID, content, messageType, BASE_TIME, deletedAt);
	}

	private void insertNotice(Long noticeId, Long roomId, Long messageId, OffsetDateTime createdAt) {
		jdbc.update("""
			INSERT INTO chat_notices (notice_id, room_id, message_id, created_by, created_at)
			VALUES (?, ?, ?, ?, ?)
			""", noticeId, roomId, messageId, CURRENT_USER_ID, createdAt);
	}

	private void pinNotice(Long roomId, Long noticeId) {
		jdbc.update("""
			UPDATE chat_rooms
			SET pinned_notice_id = ?
			WHERE room_id = ?
			""", noticeId, roomId);
	}

	private Long pinnedNoticeId(Long roomId) {
		return jdbc.queryForObject(
			"SELECT pinned_notice_id FROM chat_rooms WHERE room_id = ?",
			Long.class,
			roomId
		);
	}
}
