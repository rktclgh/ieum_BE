package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.chat.dto.ChatCursorPage;
import shinhan.fibri.ieum.main.chat.dto.ChatMessageResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomResponse;
import shinhan.fibri.ieum.main.chat.dto.SendChatMessageRequest;
import shinhan.fibri.ieum.main.friend.service.FriendRequestNotifier;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	ChatService.class,
	ChatMessageService.class,
	ChatRoomLifecycleService.class,
	OneToOneChatMemberService.class,
	FriendService.class
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

	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private FriendRequestNotifier friendRequestNotifier;

	@MockitoBean
	private RoomEventPublisher roomEventPublisher;

	@MockitoBean
	private ChatNotificationPublisher chatNotificationPublisher;

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
	void relationshipFreeQuestionRoomUsesPerMemberCutoffsAcrossPostLeaveAndSend() {
		long authorId = insertUser("visibility-author");
		long requesterId = insertUser("visibility-requester");
		long targetId = insertUser("visibility-target");
		long controlTargetId = insertUser("visibility-control-target");
		long questionId = insertQuestion(authorId, "integration-question");

		assertThat(requesterId).isNotEqualTo(authorId);
		assertThat(jdbc.queryForObject(
			"SELECT count(*) FROM answers WHERE question_id = ?",
			Integer.class,
			questionId
		)).isZero();

		ChatRoomResponse created = chatService.createQuestionRoom(
			principal(requesterId),
			questionId,
			targetId
		);
		assertThat(created.questionTitle()).isEqualTo("integration-question");

		long old1 = insertTextMessage(created.roomId(), requesterId, "old-1");
		long old2 = insertTextMessage(created.roomId(), targetId, "old-2");
		assertThat(old2).isGreaterThan(old1);

		chatService.leaveRoom(principal(requesterId), created.roomId());
		chatService.leaveRoom(principal(targetId), created.roomId());

		ChatRoomResponse reopened = chatService.createQuestionRoom(
			principal(requesterId),
			questionId,
			targetId
		);
		assertThat(reopened.roomId()).isEqualTo(created.roomId());
		assertThat(memberState(created.roomId(), requesterId))
			.isEqualTo(new MemberState(true, old2));
		assertThat(memberState(created.roomId(), targetId))
			.isEqualTo(new MemberState(false, 0));
		assertThat(messages(requesterId, created.roomId()).items()).isEmpty();

		ChatMessageResponse newMessage = chatMessageService.send(
			principal(requesterId),
			created.roomId(),
			new SendChatMessageRequest("new-message", null)
		);

		assertThat(newMessage.messageId()).isGreaterThan(old2);
		assertThat(memberState(created.roomId(), targetId))
			.isEqualTo(new MemberState(true, old2));
		assertThat(messages(requesterId, created.roomId()).items())
			.extracting(ChatMessageResponse::content)
			.containsExactly("new-message");
		assertThat(messages(targetId, created.roomId()).items())
			.extracting(ChatMessageResponse::content)
			.containsExactly("new-message");

		chatService.createQuestionRoom(principal(requesterId), questionId, targetId);
		assertThat(memberState(created.roomId(), requesterId))
			.isEqualTo(new MemberState(true, old2));

		ChatRoomResponse controlRoom = chatService.createQuestionRoom(
			principal(requesterId),
			questionId,
			controlTargetId
		);
		insertTextMessage(controlRoom.roomId(), requesterId, "control-old-1");
		insertTextMessage(controlRoom.roomId(), requesterId, "control-old-2");

		assertThat(memberState(controlRoom.roomId(), controlTargetId))
			.isEqualTo(new MemberState(true, 0));
		assertThat(messages(controlTargetId, controlRoom.roomId()).items())
			.extracting(ChatMessageResponse::content)
			.containsExactly("control-old-2", "control-old-1");
	}

	@Test
	void failedImageInsertRollsBackRecipientActivationAndMessage() {
		long authorId = insertUser("rollback-author");
		long senderId = insertUser("rollback-sender");
		long targetId = insertUser("rollback-target");
		long questionId = insertQuestion(authorId, "rollback-question");
		ChatRoomResponse room = chatService.createQuestionRoom(principal(senderId), questionId, targetId);
		long oldMessageId = insertTextMessage(room.roomId(), senderId, "existing-message");
		chatService.leaveRoom(principal(targetId), room.roomId());

		assertThatThrownBy(() -> chatMessageService.send(
			principal(senderId),
			room.roomId(),
			new SendChatMessageRequest(null, UUID.fromString("11111111-1111-1111-1111-111111111111"))
		)).isInstanceOf(DataIntegrityViolationException.class);

		RollbackSnapshot snapshot = rollbackSnapshotInRequiresNew(room.roomId(), targetId);
		assertThat(snapshot.memberState()).isEqualTo(new MemberState(false, 0));
		assertThat(snapshot.messageCount()).isOne();
		assertThat(snapshot.maxMessageId()).isEqualTo(oldMessageId);
		verifyNoInteractions(roomEventPublisher, chatNotificationPublisher);
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

	private RollbackSnapshot rollbackSnapshotInRequiresNew(long roomId, long userId) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		transaction.setReadOnly(true);
		return transaction.execute(status -> new RollbackSnapshot(
			memberState(roomId, userId),
			jdbc.queryForObject(
				"SELECT count(*) FROM messages WHERE room_id = ?",
				Long.class,
				roomId
			),
			jdbc.queryForObject(
				"SELECT COALESCE(MAX(message_id), 0) FROM messages WHERE room_id = ?",
				Long.class,
				roomId
			)
		));
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

	private record RollbackSnapshot(MemberState memberState, long messageCount, long maxMessageId) {
	}
}
