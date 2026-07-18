package shinhan.fibri.ieum.main.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import javax.sql.DataSource;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.MessageType;
import shinhan.fibri.ieum.main.chat.dto.ChatMessageResponse;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.chat.service.ChatRoomLifecycleService;
import shinhan.fibri.ieum.main.chat.service.ChatRoomListChangeEmitter;
import shinhan.fibri.ieum.main.chat.service.ChatRoomSummaryQueryService;
import shinhan.fibri.ieum.main.chat.service.ChatService;
import shinhan.fibri.ieum.main.chat.service.ChatSystemMessageService;
import shinhan.fibri.ieum.main.chat.service.RoomEventPublisher;
import shinhan.fibri.ieum.main.chat.service.WsMessageEvent;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.main.meeting.dto.KickMeetingRequest;
import shinhan.fibri.ieum.main.meeting.exception.ParticipantNotFoundException;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;
import shinhan.fibri.ieum.testsupport.OfflineUserPresenceQueryConfiguration;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	MeetingService.class,
	ChatRoomLifecycleService.class,
	ChatSystemMessageService.class,
	ChatRoomListChangeEmitter.class,
	ChatService.class,
	ChatRoomSummaryQueryService.class,
	OfflineUserPresenceQueryConfiguration.class,
	MeetingDepartureMessageIntegrationTest.PublisherConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class MeetingDepartureMessageIntegrationTest {

	private static final String DATABASE = "meeting_departure_messages";

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
		registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
	}

	@Autowired
	private MeetingService meetingService;

	@Autowired
	private ChatService chatService;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private RecordingRoomEventPublisher roomEventPublisher;

	@MockitoBean
	private PinWriter pinWriter;

	@MockitoBean
	private FriendService friendService;

	@BeforeEach
	void setUp() {
		roomEventPublisher.clear();
		jdbc.sql("TRUNCATE TABLE users RESTART IDENTITY CASCADE").update();
	}

	@Test
	void leavePersistsAndPublishesNeutralDepartureMessageForRemainingMemberOnly() {
		DepartureFixture fixture = departureFixture();

		meetingService.leave(principal(fixture.departingUserId()), fixture.meetingId());

		assertThat(participantStatus(fixture)).isEqualTo("left");
		assertThat(memberIsActive(fixture.roomId(), fixture.departingUserId())).isFalse();
		assertNeutralSystemMessage(fixture);
		assertThat(roomEventPublisher.events())
			.singleElement()
			.satisfies(event -> {
				assertThat(event.roomId()).isEqualTo(fixture.roomId());
				assertThat(event.senderId()).isEqualTo(fixture.departingUserId());
				assertThat(event.senderNickname()).isEqualTo("민지");
				assertThat(event.messageType()).isEqualTo(MessageType.system);
				assertThat(event.content()).isEqualTo("민지님이 모임을 떠났습니다");
				assertThat(event.imageUrl()).isNull();
			});
		assertThat(chatService.listMessages(principal(fixture.hostUserId()), fixture.roomId(), null, 20).items())
			.singleElement()
			.satisfies(message -> assertNeutralResponse(message, fixture));
		assertThatThrownBy(() -> chatService.listMessages(
			principal(fixture.departingUserId()), fixture.roomId(), null, 20
		)).isInstanceOf(NotRoomMemberException.class);
	}

	@Test
	void kickPersistsAndPublishesTheSameNeutralDepartureMessageForRemainingMemberOnly() {
		DepartureFixture fixture = departureFixture();

		meetingService.kick(principal(fixture.hostUserId()), fixture.meetingId(), new KickMeetingRequest(fixture.departingUserId()));

		assertThat(participantStatus(fixture)).isEqualTo("kicked");
		assertThat(memberIsActive(fixture.roomId(), fixture.departingUserId())).isFalse();
		assertNeutralSystemMessage(fixture);
		assertThat(roomEventPublisher.events())
			.singleElement()
			.satisfies(event -> {
				assertThat(event.roomId()).isEqualTo(fixture.roomId());
				assertThat(event.senderId()).isEqualTo(fixture.departingUserId());
				assertThat(event.senderNickname()).isEqualTo("민지");
				assertThat(event.messageType()).isEqualTo(MessageType.system);
				assertThat(event.content()).isEqualTo("민지님이 모임을 떠났습니다");
				assertThat(event.imageUrl()).isNull();
			});
		assertThat(chatService.listMessages(principal(fixture.hostUserId()), fixture.roomId(), null, 20).items())
			.singleElement()
			.satisfies(message -> assertNeutralResponse(message, fixture));
		assertThatThrownBy(() -> chatService.listMessages(
			principal(fixture.departingUserId()), fixture.roomId(), null, 20
		)).isInstanceOf(NotRoomMemberException.class);
	}

	@Test
	void leaveKeepsLegacyMissingChatMemberToleranceWithoutFabricatingDepartureMessage() {
		DepartureFixture fixture = departureFixture();
		removeChatMember(fixture);

		meetingService.leave(principal(fixture.departingUserId()), fixture.meetingId());

		assertThat(participantStatus(fixture)).isEqualTo("left");
		assertThat(countSystemMessages(fixture)).isZero();
		assertThat(roomEventPublisher.events()).isEmpty();
	}

	@Test
	void kickKeepsLegacyMissingChatMemberToleranceWithoutFabricatingDepartureMessage() {
		DepartureFixture fixture = departureFixture();
		removeChatMember(fixture);

		meetingService.kick(principal(fixture.hostUserId()), fixture.meetingId(), new KickMeetingRequest(fixture.departingUserId()));

		assertThat(participantStatus(fixture)).isEqualTo("kicked");
		assertThat(countSystemMessages(fixture)).isZero();
		assertThat(roomEventPublisher.events()).isEmpty();
	}

	@Test
	void concurrentLeaveRequestsPersistExactlyOneDepartureSystemMessage() throws Exception {
		DepartureFixture fixture = departureFixture();

		List<Throwable> outcomes = runConcurrently(
			fixture,
			() -> meetingService.leave(principal(fixture.departingUserId()), fixture.meetingId()),
			() -> meetingService.leave(principal(fixture.departingUserId()), fixture.meetingId())
		);

		assertOneDepartureSucceeded(outcomes, fixture);
		assertThat(participantStatus(fixture)).isEqualTo("left");
	}

	@Test
	void concurrentLeaveAndKickPersistExactlyOneDepartureSystemMessage() throws Exception {
		DepartureFixture fixture = departureFixture();

		List<Throwable> outcomes = runConcurrently(
			fixture,
			() -> meetingService.leave(principal(fixture.departingUserId()), fixture.meetingId()),
			() -> meetingService.kick(
				principal(fixture.hostUserId()), fixture.meetingId(), new KickMeetingRequest(fixture.departingUserId())
			)
		);

		assertOneDepartureSucceeded(outcomes, fixture);
		assertThat(participantStatus(fixture)).isIn("left", "kicked");
	}

	private List<Throwable> runConcurrently(
		DepartureFixture fixture,
		ThrowingRunnable first,
		ThrowingRunnable second
	) throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try (Connection departureGate = dataSource.getConnection()) {
			departureGate.setAutoCommit(false);
			lockParticipantDeparture(departureGate, fixture);
			Future<Throwable> firstOutcome = executor.submit(invokeAfterStart(ready, start, first));
			Future<Throwable> secondOutcome = executor.submit(invokeAfterStart(ready, start, second));
			assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			awaitParticipantLockWaiters();
			departureGate.commit();
			return Arrays.asList(firstOutcome.get(10, TimeUnit.SECONDS), secondOutcome.get(10, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
		}
	}

	private void lockParticipantDeparture(Connection connection, DepartureFixture fixture) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement("""
			SELECT 1
			FROM meeting_participants
			WHERE meeting_id = ? AND user_id = ?
			FOR UPDATE
			""")) {
			statement.setLong(1, fixture.meetingId());
			statement.setLong(2, fixture.departingUserId());
			statement.executeQuery().close();
		}
	}

	private void awaitParticipantLockWaiters() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			long waiters = jdbc.sql("""
				SELECT count(*)
				FROM pg_stat_activity
				WHERE datname = current_database()
				  AND wait_event_type = 'Lock'
				  AND query LIKE '%meeting_participants%'
				""")
				.query(Long.class)
				.single();
			if (waiters >= 2) {
				return;
			}
			TimeUnit.MILLISECONDS.sleep(25);
		}
		throw new IllegalStateException("Timed out waiting for concurrent participant locks");
	}

	private Callable<Throwable> invokeAfterStart(
		CountDownLatch ready,
		CountDownLatch start,
		ThrowingRunnable invocation
	) {
		return () -> {
			ready.countDown();
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Timed out waiting to start concurrent departure");
			}
			try {
				invocation.run();
				return null;
			} catch (Throwable exception) {
				return exception;
			}
		};
	}

	private void assertOneDepartureSucceeded(List<Throwable> outcomes, DepartureFixture fixture) {
		assertThat(outcomes).filteredOn(Objects::isNull).hasSize(1);
		assertThat(outcomes).filteredOn(Objects::nonNull)
			.singleElement()
			.isInstanceOf(ParticipantNotFoundException.class);
		assertThat(countSystemMessages(fixture)).isEqualTo(1);
		assertThat(roomEventPublisher.events()).hasSize(1);
	}

	private DepartureFixture departureFixture() {
		long hostUserId = insertUser("호스트");
		long departingUserId = insertUser("민지");
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (:hostUserId, 'meeting', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울특별시')
			RETURNING pin_id
			""")
			.param("hostUserId", hostUserId)
			.query(Long.class)
			.single();
		long meetingId = jdbc.sql("""
			INSERT INTO meetings (pin_id, host_id, title, content, type, max_members)
			VALUES (:pinId, :hostUserId, '퇴장 메시지 모임', 'content', 'one_time', 2)
			RETURNING meeting_id
			""")
			.param("pinId", pinId)
			.param("hostUserId", hostUserId)
			.query(Long.class)
			.single();
		jdbc.sql("""
			INSERT INTO meeting_participants (meeting_id, user_id, status)
			VALUES (:meetingId, :hostUserId, 'joined'), (:meetingId, :departingUserId, 'joined')
			""")
			.param("meetingId", meetingId)
			.param("hostUserId", hostUserId)
			.param("departingUserId", departingUserId)
			.update();
		long roomId = jdbc.sql("""
			INSERT INTO chat_rooms (room_type, meeting_id)
			VALUES ('group', :meetingId)
			RETURNING room_id
			""")
			.param("meetingId", meetingId)
			.query(Long.class)
			.single();
		jdbc.sql("""
			INSERT INTO chat_members (room_id, user_id)
			VALUES (:roomId, :hostUserId), (:roomId, :departingUserId)
			""")
			.param("roomId", roomId)
			.param("hostUserId", hostUserId)
			.param("departingUserId", departingUserId)
			.update();
		return new DepartureFixture(meetingId, roomId, hostUserId, departingUserId);
	}

	private long insertUser(String nickname) {
		return jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", nickname + "-" + System.nanoTime() + "@example.com")
			.param("nickname", nickname)
			.query(Long.class)
			.single();
	}

	private String participantStatus(DepartureFixture fixture) {
		return jdbc.sql("""
			SELECT status::text
			FROM meeting_participants
			WHERE meeting_id = :meetingId AND user_id = :departingUserId
			""")
			.param("meetingId", fixture.meetingId())
			.param("departingUserId", fixture.departingUserId())
			.query(String.class)
			.single();
	}

	private boolean memberIsActive(long roomId, long userId) {
		return jdbc.sql("""
			SELECT left_at IS NULL
			FROM chat_members
			WHERE room_id = :roomId AND user_id = :userId
			""")
			.param("roomId", roomId)
			.param("userId", userId)
			.query(Boolean.class)
			.single();
	}

	private void removeChatMember(DepartureFixture fixture) {
		jdbc.sql("""
			DELETE FROM chat_members
			WHERE room_id = :roomId AND user_id = :departingUserId
			""")
			.param("roomId", fixture.roomId())
			.param("departingUserId", fixture.departingUserId())
			.update();
	}

	private void assertNeutralSystemMessage(DepartureFixture fixture) {
		assertThat(countSystemMessages(fixture)).isEqualTo(1);
		assertThat(jdbc.sql("""
			SELECT sender_id = :departingUserId
			   AND message_type = 'system'
			   AND content = '민지님이 모임을 떠났습니다'
			   AND image_file_id IS NULL
			FROM messages
			WHERE room_id = :roomId
			""")
			.param("departingUserId", fixture.departingUserId())
			.param("roomId", fixture.roomId())
			.query(Boolean.class)
			.single()).isTrue();
	}

	private long countSystemMessages(DepartureFixture fixture) {
		return jdbc.sql("""
			SELECT count(*)
			FROM messages
			WHERE room_id = :roomId AND message_type = 'system'
			""")
			.param("roomId", fixture.roomId())
			.query(Long.class)
			.single();
	}

	private void assertNeutralResponse(ChatMessageResponse message, DepartureFixture fixture) {
		assertThat(message.roomId()).isEqualTo(fixture.roomId());
		assertThat(message.senderId()).isEqualTo(fixture.departingUserId());
		assertThat(message.messageType()).isEqualTo(MessageType.system);
		assertThat(message.content()).isEqualTo("민지님이 모임을 떠났습니다");
		assertThat(message.imageUrl()).isNull();
	}

	private AuthenticatedUser principal(long userId) {
		return new AuthenticatedUser(
			userId,
			"user-%d@example.com".formatted(userId),
			UserRole.user,
			UserStatus.active
		);
	}

	@TestConfiguration
	static class PublisherConfiguration {

		@Bean
		@Primary
		RecordingRoomEventPublisher recordingRoomEventPublisher() {
			return new RecordingRoomEventPublisher();
		}
	}

	static class RecordingRoomEventPublisher implements RoomEventPublisher {

		private final List<WsMessageEvent> events = new CopyOnWriteArrayList<>();

		@Override
		public void publish(WsMessageEvent event) {
			events.add(event);
		}

		@Override
		public void publishUserMessage(WsMessageEvent event, List<ChatMember> recipients) {
			recipients.forEach(ignored -> events.add(event));
		}

		List<WsMessageEvent> events() {
			return List.copyOf(events);
		}

		void clear() {
			events.clear();
		}
	}

	private record DepartureFixture(long meetingId, long roomId, long hostUserId, long departingUserId) {
	}

	@FunctionalInterface
	private interface ThrowingRunnable {

		void run();
	}
}
