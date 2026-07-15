package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomListEvent;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({
	ChatRoomSummaryQueryService.class,
	ChatRoomListChangeEmitter.class,
	ChatRoomListChangeListener.class,
	ChatRoomListChangeTransactionIntegrationTest.PublisherConfiguration.class
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ChatRoomListChangeTransactionIntegrationTest {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "chat_room_list_change_transaction");
	}

	@Autowired
	private ChatRoomListChangeEmitter emitter;

	@Autowired
	private RecordingChatRoomListEventPublisher publisher;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcClient jdbc;

	@BeforeEach
	void setUp() {
		publisher.clear();
		jdbc.sql("TRUNCATE TABLE users RESTART IDENTITY CASCADE").update();
	}

	@Test
	void emitterUpsertInsideTransactionPublishesAuthoritativeSummaryOnlyAfterCommit() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		Long[] ids = new Long[3];

		transaction.execute(status -> {
			ids[0] = insertUser("commit-me");
			ids[1] = insertUser("commit-friend");
			ids[2] = insertDirectRoom(ids[0], ids[1]);
			insertActiveMember(ids[2], ids[0]);
			insertActiveMember(ids[2], ids[1]);
			insertMessage(ids[2], ids[1], "after commit");

			emitter.upsert(ids[2], List.of(ids[0]));

			assertThat(publisher.deliveries()).isEmpty();
			return null;
		});

		assertThat(publisher.deliveries())
			.singleElement()
			.satisfies(delivery -> {
				assertThat(delivery.userId()).isEqualTo(ids[0]);
				assertThat(delivery.event().type()).isEqualTo("upsert");
				assertThat(delivery.event().room().roomId()).isEqualTo(ids[2]);
				assertThat(delivery.event().room().unreadCount()).isEqualTo(1L);
				assertThat(delivery.event().room().lastMessage().content()).isEqualTo("after commit");
			});
	}

	@Test
	void emitterUpsertInsideRolledBackTransactionDoesNotPublishAfterCompletion() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.execute(status -> {
			Long me = insertUser("rollback-me");
			Long friend = insertUser("rollback-friend");
			Long room = insertDirectRoom(me, friend);
			insertActiveMember(room, me);
			insertActiveMember(room, friend);
			insertMessage(room, friend, "rolled back");

			emitter.upsert(room, List.of(me));
			assertThat(publisher.deliveries()).isEmpty();
			status.setRollbackOnly();
			return null;
		});

		assertThat(publisher.deliveries()).isEmpty();
	}

	private Long insertUser(String nicknamePrefix) {
		String suffix = UUID.randomUUID().toString();
		return jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", nicknamePrefix + "-" + suffix + "@example.com")
			.param("nickname", nicknamePrefix + "-" + suffix.substring(0, 8))
			.query(Long.class)
			.single();
	}

	private Long insertDirectRoom(Long firstUserId, Long secondUserId) {
		return jdbc.sql("""
			INSERT INTO chat_rooms (room_type, room_key)
			VALUES ('direct', :roomKey)
			RETURNING room_id
			""")
			.param("roomKey", "d:%d:%d".formatted(Math.min(firstUserId, secondUserId), Math.max(firstUserId, secondUserId)))
			.query(Long.class)
			.single();
	}

	private void insertActiveMember(Long roomId, Long userId) {
		jdbc.sql("""
			INSERT INTO chat_members (room_id, user_id)
			VALUES (:roomId, :userId)
			""")
			.param("roomId", roomId)
			.param("userId", userId)
			.update();
	}

	private void insertMessage(Long roomId, Long senderId, String content) {
		jdbc.sql("""
			INSERT INTO messages (room_id, sender_id, content)
			VALUES (:roomId, :senderId, :content)
			""")
			.param("roomId", roomId)
			.param("senderId", senderId)
			.param("content", content)
			.update();
	}

	@TestConfiguration
	static class PublisherConfiguration {

		@Bean
		RecordingChatRoomListEventPublisher recordingChatRoomListEventPublisher() {
			return new RecordingChatRoomListEventPublisher();
		}
	}

	static class RecordingChatRoomListEventPublisher implements ChatRoomListEventPublisher {

		private final List<Delivery> deliveries = new ArrayList<>();

		@Override
		public void publish(Long userId, ChatRoomListEvent event) {
			deliveries.add(new Delivery(userId, event));
		}

		List<Delivery> deliveries() {
			return List.copyOf(deliveries);
		}

		void clear() {
			deliveries.clear();
		}
	}

	record Delivery(Long userId, ChatRoomListEvent event) {
	}
}
