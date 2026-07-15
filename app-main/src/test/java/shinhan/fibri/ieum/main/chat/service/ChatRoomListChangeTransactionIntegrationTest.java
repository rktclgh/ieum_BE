package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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

	@Test
	void emitterRemoveInsideRolledBackRetryTransactionDoesNotPublishFirstAttempt() {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		Long[] ids = new Long[3];
		transaction.execute(status -> {
			ids[0] = insertUser("rollback-remove-me");
			ids[1] = insertUser("rollback-remove-friend");
			ids[2] = insertDirectRoom(ids[0], ids[1]);
			insertActiveMember(ids[2], ids[0]);
			insertActiveMember(ids[2], ids[1]);
			return null;
		});

		transaction.execute(status -> {
			leaveMember(ids[2], ids[0]);
			emitter.remove(ids[2], List.of(ids[0]));
			status.setRollbackOnly();
			return null;
		});
		assertThat(publisher.deliveries()).isEmpty();

		transaction.execute(status -> {
			leaveMember(ids[2], ids[0]);
			emitter.remove(ids[2], List.of(ids[0]));
			return null;
		});

		assertThat(publisher.deliveries())
			.singleElement()
			.satisfies(delivery -> {
				assertThat(delivery.userId()).isEqualTo(ids[0]);
				assertThat(delivery.event().type()).isEqualTo("remove");
				assertThat(delivery.event().roomId()).isEqualTo(ids[2]);
			});
	}

	@Test
	void concurrentRemoveWaitsUntilInFlightUpsertPublishCompletesBeforePublishingRemove() throws Exception {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		Long[] ids = new Long[3];
		transaction.execute(status -> {
			ids[0] = insertUser("ordered-me");
			ids[1] = insertUser("ordered-friend");
			ids[2] = insertDirectRoom(ids[0], ids[1]);
			insertActiveMember(ids[2], ids[0]);
			insertActiveMember(ids[2], ids[1]);
			return null;
		});
		CountDownLatch upsertReachedPublisher = new CountDownLatch(1);
		CountDownLatch releaseUpsertPublisher = new CountDownLatch(1);
		CountDownLatch removePublished = new CountDownLatch(1);
		publisher.blockNextUpsertBeforeRecording(upsertReachedPublisher, releaseUpsertPublisher);
		publisher.countDownRemovePublish(removePublished);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		Future<?> upsert = executor.submit(() -> transaction.execute(status -> {
			insertMessage(ids[2], ids[1], "in flight");
			emitter.upsert(ids[2], List.of(ids[0]));
			return null;
		}));
		assertThat(upsertReachedPublisher.await(5, TimeUnit.SECONDS))
			.as("upsert summary must be read and held at the publisher boundary")
			.isTrue();

		Future<?> remove = executor.submit(() -> transaction.execute(status -> {
			leaveMember(ids[2], ids[0]);
			emitter.remove(ids[2], List.of(ids[0]));
			return null;
		}));

		try {
			assertThat(removePublished.await(500, TimeUnit.MILLISECONDS))
				.as("remove must wait for the in-flight upsert lock instead of publishing before it")
				.isFalse();
		} finally {
			releaseUpsertPublisher.countDown();
			upsert.get(5, TimeUnit.SECONDS);
			remove.get(5, TimeUnit.SECONDS);
			executor.shutdownNow();
		}

		assertThat(publisher.deliveries())
			.extracting(delivery -> delivery.event().type())
			.containsExactly("upsert", "remove");
		assertThat(publisher.deliveries().get(0).event().room().roomId()).isEqualTo(ids[2]);
		assertThat(publisher.deliveries().get(1).event().roomId()).isEqualTo(ids[2]);
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

	private void leaveMember(Long roomId, Long userId) {
		jdbc.sql("""
			UPDATE chat_members
			SET left_at = now()
			WHERE room_id = :roomId
			  AND user_id = :userId
			""")
			.param("roomId", roomId)
			.param("userId", userId)
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

		private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
		private final AtomicBoolean blockNextUpsert = new AtomicBoolean();
		private CountDownLatch upsertReachedPublisher = new CountDownLatch(0);
		private CountDownLatch releaseUpsertPublisher = new CountDownLatch(0);
		private CountDownLatch removePublished = new CountDownLatch(0);

		@Override
		public void publish(Long userId, ChatRoomListEvent event) {
			if ("upsert".equals(event.type()) && blockNextUpsert.compareAndSet(true, false)) {
				upsertReachedPublisher.countDown();
				await(releaseUpsertPublisher);
			}
			deliveries.add(new Delivery(userId, event));
			if ("remove".equals(event.type())) {
				removePublished.countDown();
			}
		}

		List<Delivery> deliveries() {
			return List.copyOf(deliveries);
		}

		void clear() {
			deliveries.clear();
			blockNextUpsert.set(false);
			upsertReachedPublisher = new CountDownLatch(0);
			releaseUpsertPublisher = new CountDownLatch(0);
			removePublished = new CountDownLatch(0);
		}

		void blockNextUpsertBeforeRecording(
			CountDownLatch upsertReachedPublisher,
			CountDownLatch releaseUpsertPublisher
		) {
			this.upsertReachedPublisher = upsertReachedPublisher;
			this.releaseUpsertPublisher = releaseUpsertPublisher;
			blockNextUpsert.set(true);
		}

		void countDownRemovePublish(CountDownLatch removePublished) {
			this.removePublished = removePublished;
		}

		private void await(CountDownLatch latch) {
			try {
				if (!latch.await(5, TimeUnit.SECONDS)) {
					throw new IllegalStateException("Timed out waiting for test publisher latch");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(exception);
			}
		}
	}

	record Delivery(Long userId, ChatRoomListEvent event) {
	}
}
