package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.main.chat.dto.ChatCursorPage;
import shinhan.fibri.ieum.main.chat.dto.ChatMessageResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomResponse;
import shinhan.fibri.ieum.main.chat.dto.SendChatMessageRequest;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.friend.service.FriendRequestNotifier;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;
import shinhan.fibri.ieum.testsupport.OfflineUserPresenceQueryConfiguration;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	ChatService.class,
	ChatMessageService.class,
	ChatRoomLifecycleService.class,
	ChatRoomSummaryQueryService.class,
	FriendService.class,
	OfflineUserPresenceQueryConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OneToOneVisibilityIntegrationTest {

	private static final String DATABASE = "ieum_one_to_one_visibility";

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
		registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
	}

	@Autowired
	private ChatService chatService;

	@Autowired
	private ChatMessageService chatMessageService;

	@Autowired
	private JdbcTemplate jdbc;

	@MockitoBean
	private FriendRequestNotifier friendRequestNotifier;

	@MockitoBean
	private RoomEventPublisher roomEventPublisher;

	@MockitoBean
	private ChatNotificationPublisher chatNotificationPublisher;

	@MockitoBean
	private ChatRoomListChangeEmitter chatRoomListChangeEmitter;

	@MockitoBean
	private ChatSystemMessageService chatSystemMessageService;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
	}

	@Test
	void questionRoomLeaveRemovesRoomAndLaterMessageRestoresOnlyNewHistory() {
		long authorId = insertUser("visibility-author");
		long requesterId = insertUser("visibility-requester");
		long peerId = insertUser("visibility-peer");
		long questionId = insertQuestion(authorId, "integration-question");

		assertThat(jdbc.queryForObject("SELECT count(*) FROM answers", Integer.class)).isZero();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM friendships", Integer.class)).isZero();

		ChatRoomResponse room = chatService.createQuestionRoom(
			principal(requesterId),
			questionId,
			peerId
		);
		assertThat(room.roomType()).isEqualTo(RoomType.question);
		assertThat(room.questionTitle()).isEqualTo("integration-question");

		long oldMessageId = insertTextMessage(room.roomId(), requesterId, "old-1");
		long cutoffMessageId = insertTextMessage(room.roomId(), peerId, "old-2");
		assertThat(cutoffMessageId).isGreaterThan(oldMessageId);

		chatService.leaveRoom(principal(requesterId), room.roomId());

		assertThat(memberState(room.roomId(), requesterId))
			.isEqualTo(new MemberState(false, cutoffMessageId));
		assertThat(memberState(room.roomId(), peerId))
			.isEqualTo(new MemberState(true, 0));
		assertThat(chatService.listRooms(principal(requesterId), RoomType.question)).isEmpty();
		assertThatThrownBy(() -> messages(requesterId, room.roomId()))
			.isInstanceOf(NotRoomMemberException.class);
		assertThat(messages(peerId, room.roomId()).items())
			.extracting(ChatMessageResponse::content)
			.containsExactly("old-2", "old-1");

		ChatMessageResponse newMessage = chatMessageService.send(
			principal(peerId),
			room.roomId(),
			new SendChatMessageRequest("new-message", null)
		);

		assertThat(newMessage.messageId()).isGreaterThan(cutoffMessageId);
		assertThat(memberState(room.roomId(), requesterId))
			.isEqualTo(new MemberState(true, cutoffMessageId));
		assertThat(messages(requesterId, room.roomId()).items())
			.extracting(ChatMessageResponse::content)
			.containsExactly("new-message");
		assertThat(messages(peerId, room.roomId()).items())
			.extracting(ChatMessageResponse::content)
			.containsExactly("new-message", "old-2", "old-1");
		assertThat(chatService.listRooms(principal(requesterId), RoomType.question))
			.singleElement()
			.satisfies(summary -> {
				assertThat(summary.lastMessage().content()).isEqualTo("new-message");
				assertThat(summary.unreadCount()).isOne();
			});
	}

	private ChatCursorPage<ChatMessageResponse> messages(long userId, long roomId) {
		return chatService.listMessages(principal(userId), roomId, null, 50);
	}

	private MemberState memberState(long roomId, long userId) {
		return jdbc.queryForObject(
			"""
			SELECT left_at IS NULL AS active, visible_after_message_id
			FROM chat_members
			WHERE room_id = ? AND user_id = ?
			""",
			(rs, rowNum) -> new MemberState(
				rs.getBoolean("active"),
				rs.getLong("visible_after_message_id")
			),
			roomId,
			userId
		);
	}

	private long insertUser(String nickname) {
		return jdbc.queryForObject("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (?, 'hash', ?, true)
			RETURNING user_id
			""", Long.class, nickname + "@example.com", nickname);
	}

	private long insertQuestion(long authorId, String title) {
		long pinId = jdbc.queryForObject("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (?, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울특별시')
			RETURNING pin_id
			""", Long.class, authorId);
		return jdbc.queryForObject("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (?, ?, ?, 'content')
			RETURNING question_id
			""", Long.class, pinId, authorId, title);
	}

	private long insertTextMessage(long roomId, long senderId, String content) {
		return jdbc.queryForObject("""
			INSERT INTO messages (room_id, sender_id, content)
			VALUES (?, ?, ?)
			RETURNING message_id
			""", Long.class, roomId, senderId, content);
	}

	private AuthenticatedUser principal(long userId) {
		return new AuthenticatedUser(
			userId,
			"user-%d@example.com".formatted(userId),
			UserRole.user,
			UserStatus.active
		);
	}

	private record MemberState(boolean active, long visibleAfterMessageId) {
	}
}
