package shinhan.fibri.ieum.main.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserAuthState;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.MessageType;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.MessageRepository;
import shinhan.fibri.ieum.main.admin.content.service.ContentPurgeService;
import shinhan.fibri.ieum.main.admin.user.scheduler.SanctionExpiryScheduler;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.CanonicalAuthStateVerifier;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.auth.session.ValidatedAuthSession;
import shinhan.fibri.ieum.main.chat.service.RoomEventPublisher;
import shinhan.fibri.ieum.main.chat.service.SimpRoomEventPublisher;
import shinhan.fibri.ieum.main.chat.service.WsMessageEvent;
import shinhan.fibri.ieum.main.meeting.domain.ParticipantStatus;
import shinhan.fibri.ieum.main.meeting.repository.MeetingParticipantRepository;
import shinhan.fibri.ieum.main.meeting.scheduler.MeetingRecurrenceExpansionScheduler;
import shinhan.fibri.ieum.main.meeting.scheduler.MeetingScheduleCompletionScheduler;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MeetingDepartureMessageHttpStompIntegrationTest {

	private static final String DATABASE = "meeting_departure_http_stomp";
	private static final String CSRF_TOKEN = "meeting-departure-csrf";
	private static final String HOST_TOKEN = "meeting-host-token";
	private static final String DEPARTING_TOKEN = "meeting-departing-token";

	private final HttpClient httpClient = HttpClient.newBuilder()
		.followRedirects(HttpClient.Redirect.NEVER)
		.build();
	private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
	}

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private MessageRepository messageRepository;

	@Autowired
	private ChatMemberRepository chatMemberRepository;

	@Autowired
	private MeetingParticipantRepository participantRepository;

	@Autowired
	private RoomEventPublisher roomEventPublisher;

	@MockitoBean
	private SessionTokenValidator sessionTokenValidator;

	@MockitoBean
	private RedisAuthSessionStore sessionStore;

	@MockitoBean
	private CanonicalAuthStateVerifier canonicalAuthStateVerifier;

	@MockitoBean
	private ContentPurgeService contentPurgeService;

	@MockitoBean
	private SanctionExpiryScheduler sanctionExpiryScheduler;

	@MockitoBean
	private MeetingRecurrenceExpansionScheduler meetingRecurrenceExpansionScheduler;

	@MockitoBean
	private MeetingScheduleCompletionScheduler meetingScheduleCompletionScheduler;

	@BeforeEach
	void setUp() {
		jdbc.sql("TRUNCATE TABLE users RESTART IDENTITY CASCADE").update();
		assertThat(roomEventPublisher).isInstanceOf(SimpRoomEventPublisher.class);
	}

	@Test
	void participantLeaveHttpCallPublishesCommittedSystemMessageAndLeavesRestHistoryForHost() throws Exception {
		DepartureFixture fixture = departureFixture();
		stubAuthenticatedSession(fixture.host(), HOST_TOKEN);
		stubAuthenticatedSession(fixture.departing(), DEPARTING_TOKEN);

		StompSession session = connect(HOST_TOKEN);
		try {
			BlockingQueue<WsMessageEvent> events = new LinkedBlockingQueue<>();
			session.subscribe("/topic/rooms/" + fixture.roomId(), frameHandler(WsMessageEvent.class, events));
			WsMessageEvent readinessProbe = awaitCurrentQueueReadiness(fixture, events);

			HttpResponse<String> response = post(
				"/api/v1/meetings/" + fixture.meetingId() + "/leave",
				DEPARTING_TOKEN,
				""
			);

			assertThat(response.statusCode()).isEqualTo(200);
			assertCommittedDeparture(fixture, ParticipantStatus.left, readinessProbe, events);
		} finally {
			session.disconnect();
		}
	}

	@Test
	void hostKickHttpCallPublishesCommittedSystemMessageAndLeavesRestHistoryForHost() throws Exception {
		DepartureFixture fixture = departureFixture();
		stubAuthenticatedSession(fixture.host(), HOST_TOKEN);
		stubAuthenticatedSession(fixture.departing(), DEPARTING_TOKEN);

		StompSession session = connect(HOST_TOKEN);
		try {
			BlockingQueue<WsMessageEvent> events = new LinkedBlockingQueue<>();
			session.subscribe("/topic/rooms/" + fixture.roomId(), frameHandler(WsMessageEvent.class, events));
			WsMessageEvent readinessProbe = awaitCurrentQueueReadiness(fixture, events);

			HttpResponse<String> response = post(
				"/api/v1/meetings/" + fixture.meetingId() + "/kick",
				HOST_TOKEN,
				"{\"userId\":" + fixture.departing().id() + "}"
			);

			assertThat(response.statusCode()).isEqualTo(200);
			assertCommittedDeparture(fixture, ParticipantStatus.kicked, readinessProbe, events);
		} finally {
			session.disconnect();
		}
	}

	private void assertCommittedDeparture(
		DepartureFixture fixture,
		ParticipantStatus expectedStatus,
		WsMessageEvent readinessProbe,
		BlockingQueue<WsMessageEvent> events
	) throws Exception {
		WsMessageEvent event = pollIgnoringReadinessProbe(events, readinessProbe, 5, TimeUnit.SECONDS);
		assertThat(event).isNotNull();
		assertNeutralDeparture(event, fixture);
		assertThat(pollIgnoringReadinessProbe(events, readinessProbe, 200, TimeUnit.MILLISECONDS)).isNull();

		assertThat(participantRepository.findByIdMeetingIdAndIdUserId(
			fixture.meetingId(), fixture.departing().id()
	))
			.get()
			.extracting(participant -> participant.getStatus())
			.isEqualTo(expectedStatus);
		assertThat(chatMemberRepository.existsByRoom_IdAndUser_IdAndLeftAtIsNull(
			fixture.roomId(), fixture.departing().id()
		)).isFalse();

		List<Message> persisted = messageRepository.findLatestVisibleMessages(
			fixture.roomId(), fixture.host().id(), PageRequest.of(0, 20)
		);
		assertThat(persisted).hasSize(1);
		Message persistedMessage = persisted.getFirst();
		assertThat(persistedMessage.getMessageType()).isEqualTo(MessageType.system);
		assertThat(persistedMessage.getSender().getId()).isEqualTo(fixture.departing().id());
		assertThat(persistedMessage.getContent()).isEqualTo("민지님이 모임을 떠났습니다");
		assertThat(persistedMessage.getImageFileId()).isNull();
		assertThat(event.messageId()).isEqualTo(persistedMessage.getId());

		HttpResponse<String> history = get(
			"/api/v1/chat/rooms/" + fixture.roomId() + "/messages?size=20",
			HOST_TOKEN
		);
		assertThat(history.statusCode()).isEqualTo(200);
		JsonNode items = objectMapper.readTree(history.body()).path("items");
		assertThat(items.isArray()).isTrue();
		assertThat(items.size()).isEqualTo(1);
		JsonNode message = items.get(0);
		assertThat(message.path("messageId").asLong()).isEqualTo(event.messageId());
		assertThat(message.path("roomId").asLong()).isEqualTo(fixture.roomId());
		assertThat(message.path("senderId").asLong()).isEqualTo(fixture.departing().id());
		assertThat(message.path("senderNickname").asText()).isEqualTo("민지");
		assertThat(message.path("messageType").asText()).isEqualTo("system");
		assertThat(message.path("content").asText()).isEqualTo("민지님이 모임을 떠났습니다");
		assertThat(message.path("imageUrl").isNull()).isTrue();

		HttpResponse<String> departedHistory = get(
			"/api/v1/chat/rooms/" + fixture.roomId() + "/messages?size=20",
			DEPARTING_TOKEN
		);
		assertThat(departedHistory.statusCode()).isEqualTo(403);
		assertThat(objectMapper.readTree(departedHistory.body()).path("code").asText()).isEqualTo("NOT_ROOM_MEMBER");
	}

	private void assertNeutralDeparture(WsMessageEvent event, DepartureFixture fixture) {
		assertThat(event.roomId()).isEqualTo(fixture.roomId());
		assertThat(event.senderId()).isEqualTo(fixture.departing().id());
		assertThat(event.senderNickname()).isEqualTo("민지");
		assertThat(event.messageType()).isEqualTo(MessageType.system);
		assertThat(event.content()).isEqualTo("민지님이 모임을 떠났습니다");
		assertThat(event.imageUrl()).isNull();
	}

	private DepartureFixture departureFixture() {
		FixtureUser host = insertUser("호스트");
		FixtureUser departing = insertUser("민지");
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (:hostUserId, 'meeting', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, '서울특별시')
			RETURNING pin_id
			""")
			.param("hostUserId", host.id())
			.query(Long.class)
			.single();
		long meetingId = jdbc.sql("""
			INSERT INTO meetings (pin_id, host_id, title, content, type, max_members)
			VALUES (:pinId, :hostUserId, 'HTTP STOMP 퇴장 메시지 모임', 'content', 'one_time', 2)
			RETURNING meeting_id
			""")
			.param("pinId", pinId)
			.param("hostUserId", host.id())
			.query(Long.class)
			.single();
		jdbc.sql("""
			INSERT INTO meeting_participants (meeting_id, user_id, status)
			VALUES (:meetingId, :hostUserId, 'joined'), (:meetingId, :departingUserId, 'joined')
			""")
			.param("meetingId", meetingId)
			.param("hostUserId", host.id())
			.param("departingUserId", departing.id())
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
			.param("hostUserId", host.id())
			.param("departingUserId", departing.id())
			.update();
		return new DepartureFixture(meetingId, roomId, host, departing);
	}

	private FixtureUser insertUser(String nickname) {
		String email = nickname + "-" + System.nanoTime() + "@example.com";
		long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", email)
			.param("nickname", nickname)
			.query(Long.class)
			.single();
		return new FixtureUser(userId, email);
	}

	private void stubAuthenticatedSession(FixtureUser user, String token) {
		AuthenticatedUser principal = new AuthenticatedUser(user.id(), user.email(), UserRole.user, UserStatus.active);
		String sessionId = "meeting-departure-session-" + user.id();
		AuthSession session = new AuthSession(
			sessionId,
			user.id(),
			user.email(),
			"refresh-hash-" + user.id(),
			null,
			UserRole.user,
			UserStatus.active,
			OffsetDateTime.parse("2026-07-16T00:00:00+09:00"),
			0L
		);
		when(sessionTokenValidator.validateSession(token))
			.thenReturn(Optional.of(new ValidatedAuthSession(principal, sessionId)));
		when(sessionStore.findBySessionId(sessionId)).thenReturn(Optional.of(session));
		when(canonicalAuthStateVerifier.findActiveMatching(session)).thenReturn(Optional.of(new UserAuthState(
			user.email(),
			UserRole.user,
			UserStatus.active,
			0L
		)));
	}

	private HttpResponse<String> post(String path, String accessToken, String body) throws Exception {
		return httpClient.send(
			HttpRequest.newBuilder()
				.uri(serverUri(path))
				.header(HttpHeaders.COOKIE, cookies(accessToken))
				.header("X-CSRF-Token", CSRF_TOKEN)
				.header(HttpHeaders.CONTENT_TYPE, "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
		);
	}

	private HttpResponse<String> get(String path, String accessToken) throws Exception {
		return httpClient.send(
			HttpRequest.newBuilder()
				.uri(serverUri(path))
				.header(HttpHeaders.COOKIE, cookies(accessToken))
				.GET()
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
		);
	}

	@SuppressWarnings("removal")
	private StompSession connect(String accessToken) throws Exception {
		WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
		MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
		converter.getObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
		client.setMessageConverter(converter);
		WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
		headers.add(HttpHeaders.COOKIE, "access_token=" + accessToken);
		return client.connectAsync(webSocketUrl(), headers, new StompSessionHandlerAdapter() {
		}).get(3, TimeUnit.SECONDS);
	}

	private String webSocketUrl() {
		return "ws://127.0.0.1:%d/ws".formatted(port);
	}

	private <T> StompFrameHandler frameHandler(Class<T> payloadType, BlockingQueue<T> values) {
		return new StompFrameHandler() {
			@Override
			public Type getPayloadType(StompHeaders headers) {
				return payloadType;
			}

			@Override
			public void handleFrame(StompHeaders headers, Object payload) {
				values.add(payloadType.cast(payload));
			}
		};
	}

	private WsMessageEvent awaitCurrentQueueReadiness(DepartureFixture fixture, BlockingQueue<WsMessageEvent> events)
		throws InterruptedException {
		String probeContent = "__meeting-departure-stomp-ready__" + UUID.randomUUID();
		WsMessageEvent probe = new WsMessageEvent(
			-System.nanoTime(),
			fixture.roomId(),
			fixture.host().id(),
			"readiness-probe",
			null,
			MessageType.system,
			probeContent,
			null,
			OffsetDateTime.parse("2026-07-16T00:00:00+09:00")
		);
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
		while (System.nanoTime() < deadline) {
			roomEventPublisher.publish(probe);
			if (currentQueueReceived(events, probe)) {
				events.clear();
				return probe;
			}
		}
		throw new AssertionError("current STOMP queue did not receive its readiness probe before the HTTP request");
	}

	private boolean currentQueueReceived(BlockingQueue<WsMessageEvent> events, WsMessageEvent probe)
		throws InterruptedException {
		WsMessageEvent event = events.poll(100, TimeUnit.MILLISECONDS);
		return event != null
			&& isReadinessProbe(event, probe);
	}

	private WsMessageEvent pollIgnoringReadinessProbe(
		BlockingQueue<WsMessageEvent> events,
		WsMessageEvent readinessProbe,
		long timeout,
		TimeUnit unit
	) throws InterruptedException {
		long deadline = System.nanoTime() + unit.toNanos(timeout);
		while (true) {
			long remainingNanos = deadline - System.nanoTime();
			if (remainingNanos <= 0) {
				return null;
			}
			WsMessageEvent event = events.poll(remainingNanos, TimeUnit.NANOSECONDS);
			if (event == null || !isReadinessProbe(event, readinessProbe)) {
				return event;
			}
		}
	}

	private boolean isReadinessProbe(WsMessageEvent event, WsMessageEvent probe) {
		return event.messageId().equals(probe.messageId())
			&& event.roomId().equals(probe.roomId())
			&& event.content().equals(probe.content());
	}

	private URI serverUri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private String cookies(String accessToken) {
		return "access_token=%s; csrf_token=%s".formatted(accessToken, CSRF_TOKEN);
	}

	private record DepartureFixture(long meetingId, long roomId, FixtureUser host, FixtureUser departing) {
	}

	private record FixtureUser(long id, String email) {
	}
}
