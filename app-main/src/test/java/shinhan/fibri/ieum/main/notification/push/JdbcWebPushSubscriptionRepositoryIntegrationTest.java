package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.HexFormat;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class JdbcWebPushSubscriptionRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_main_web_push_subscriptions";

	private JdbcClient jdbc;
	private WebPushSubscriptionRepository repository;
	private TransactionTemplate transaction;

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		DataSource dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = JdbcClient.create(dataSource);
		repository = new JdbcWebPushSubscriptionRepository(jdbc);
		transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
		insertUser(1L, "push-one@example.com", "push-one");
		insertUser(2L, "push-two@example.com", "push-two");
	}

	@Test
	void insertsVersionOneAndHashesTheEndpointServerSide() {
		String endpoint = "https://push.example/subscriptions/one";

		WebPushSubscription stored = upsert(input(
			1L, "session-1", endpoint, "p256dh-1", "auth-1", null
		));

		assertThat(stored.subscriptionId()).isPositive();
		assertThat(stored.userId()).isEqualTo(1L);
		assertThat(stored.sessionId()).isEqualTo("session-1");
		assertThat(stored.endpoint()).isEqualTo(endpoint);
		assertThat(stored.p256dh()).isEqualTo("p256dh-1");
		assertThat(stored.authSecret()).isEqualTo("auth-1");
		assertThat(stored.bindingVersion()).isEqualTo(1L);
		assertThat(stored.createdAt()).isNotNull();
		assertThat(stored.updatedAt()).isNotNull();
		assertThat(endpointHash(stored.subscriptionId())).isEqualTo(sha256(endpoint));
	}

	@Test
	void updatesKeysAndExpiryWithoutChangingVersionForTheSameBinding() {
		String endpoint = "https://push.example/subscriptions/same-binding";
		WebPushSubscription first = upsert(input(
			1L, "session-1", endpoint, "old-key", "old-auth", null
		));
		OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);

		WebPushSubscription updated = upsert(input(
			1L, "session-1", endpoint, "new-key", "new-auth", expiry
		));

		assertThat(updated.subscriptionId()).isEqualTo(first.subscriptionId());
		assertThat(updated.bindingVersion()).isEqualTo(first.bindingVersion());
		assertThat(updated.p256dh()).isEqualTo("new-key");
		assertThat(updated.authSecret()).isEqualTo("new-auth");
		assertThat(updated.expiresAt()).isEqualTo(expiry);
	}

	@Test
	void atomicallyRebindsTheEndpointAndIncrementsTheBindingVersion() {
		String endpoint = "https://push.example/subscriptions/rebound";
		WebPushSubscription first = upsert(input(
			1L, "session-1", endpoint, "key-1", "auth-1", null
		));

		WebPushSubscription rebound = upsert(input(
			2L, "session-2", endpoint, "key-2", "auth-2", null
		));

		assertThat(rebound.subscriptionId()).isEqualTo(first.subscriptionId());
		assertThat(rebound.bindingVersion()).isEqualTo(2L);
		assertThat(rebound.userId()).isEqualTo(2L);
		assertThat(rebound.sessionId()).isEqualTo("session-2");
		assertThat(rebound.p256dh()).isEqualTo("key-2");
		assertThat(rebound.authSecret()).isEqualTo("auth-2");
		assertThat(subscriptionCount()).isEqualTo(1);
	}

	@Test
	void staleConditionalDeleteCannotRemoveAReboundSubscription() {
		String endpoint = "https://push.example/subscriptions/fenced-delete";
		WebPushSubscription first = upsert(input(
			1L, "session-1", endpoint, "key-1", "auth-1", null
		));
		WebPushSubscription rebound = upsert(input(
			2L, "session-2", endpoint, "key-2", "auth-2", null
		));

		assertThat(repository.deleteByIdAndBindingVersion(
			first.subscriptionId(), first.bindingVersion()
		)).isFalse();
		assertThat(subscriptionCount()).isEqualTo(1);
		assertThat(repository.deleteByIdAndBindingVersion(
			rebound.subscriptionId(), rebound.bindingVersion()
		)).isTrue();
		assertThat(subscriptionCount()).isZero();
	}

	@Test
	void sameSessionChangingEndpointLeavesOnlyLatestSubscription() {
		WebPushSubscription first = upsert(input(
			1L, "session-1", endpoint("old"), "key-1", "auth-1", null
		));

		WebPushSubscription latest = upsert(input(
			1L, "session-1", endpoint("latest"), "key-2", "auth-2", null
		));

		assertThat(latest.subscriptionId()).isNotEqualTo(first.subscriptionId());
		assertThat(repository.findActiveByUserId(1L))
			.extracting(WebPushSubscription::endpoint)
			.containsExactly(endpoint("latest"));
		assertThat(sessionSubscriptionCount("session-1")).isOne();
		assertThat(repository.deleteByIdAndBindingVersion(
			first.subscriptionId(), first.bindingVersion()
		)).isFalse();
	}

	@Test
	void sessionCanRebindEndpointOwnedByAnotherSession() {
		WebPushSubscription endpointX = upsert(input(
			1L, "session-a", endpoint("x"), "key-x", "auth-x", null
		));
		WebPushSubscription endpointY = upsert(input(
			2L, "session-b", endpoint("y"), "key-y", "auth-y", null
		));

		WebPushSubscription rebound = upsert(input(
			1L, "session-a", endpoint("y"), "key-new", "auth-new", null
		));

		assertThat(rebound.subscriptionId()).isEqualTo(endpointY.subscriptionId());
		assertThat(rebound.bindingVersion()).isEqualTo(endpointY.bindingVersion() + 1);
		assertThat(rebound.userId()).isEqualTo(1L);
		assertThat(rebound.sessionId()).isEqualTo("session-a");
		assertThat(subscriptionCount()).isOne();
		assertThat(repository.deleteByIdAndBindingVersion(
			endpointX.subscriptionId(), endpointX.bindingVersion()
		)).isFalse();
		assertThat(repository.deleteByIdAndBindingVersion(
			endpointY.subscriptionId(), endpointY.bindingVersion()
		)).isFalse();
	}

	@Test
	void concurrentRegistrationsForSameSessionNeverCreateDuplicatesOrThrow() throws Exception {
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<WebPushSubscription> first = executor.submit(() -> concurrentUpsert(
				ready, start, input(1L, "shared-session", endpoint("one"), "key-1", "auth-1", null)
			));
			Future<WebPushSubscription> second = executor.submit(() -> concurrentUpsert(
				ready, start, input(1L, "shared-session", endpoint("two"), "key-2", "auth-2", null)
			));
			ready.await();
			start.countDown();

			assertThat(List.of(first.get(), second.get())).hasSize(2);
		}

		assertThat(sessionSubscriptionCount("shared-session")).isOne();
	}

	@Test
	void cleanupAndUpsertAreAtomic() {
		WebPushSubscription original = upsert(input(
			1L, "session-atomic", endpoint("original"), "key-old", "auth-old", null
		));
		installFailureAfterCleanupTrigger();

		assertThatThrownBy(() -> upsert(input(
			1L, "session-atomic", endpoint("forced-failure"), "key-new", "auth-new", null
		))).isInstanceOf(RuntimeException.class);

		assertThat(repository.findActiveByUserId(1L))
			.extracting(WebPushSubscription::subscriptionId, WebPushSubscription::endpoint)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				original.subscriptionId(), endpoint("original")
			));
	}

	@Test
	void deletesAllSubscriptionsBySessionAndByUser() {
		upsert(input(1L, "session-a", endpoint("a"), "key", "auth", null));
		upsert(input(1L, "session-b", endpoint("b"), "key", "auth", null));
		upsert(input(2L, "session-c", endpoint("c"), "key", "auth", null));

		assertThat(repository.deleteAllBySessionId("session-a")).isEqualTo(1);
		assertThat(repository.deleteAllByUserId(1L)).isEqualTo(1);
		assertThat(repository.findActiveByUserId(1L)).isEmpty();
		assertThat(repository.findActiveByUserId(2L))
			.extracting(WebPushSubscription::endpoint)
			.containsExactly(endpoint("c"));
	}

	@Test
	void findsWhetherTheCurrentUserSessionHasAnActiveSubscription() {
		OffsetDateTime expired = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
		upsert(input(1L, "active-session", endpoint("active"), "key", "auth", null));
		upsert(input(1L, "expired-session", endpoint("expired"), "key", "auth", expired));

		assertThat(repository.existsActiveByUserIdAndSessionId(1L, "active-session")).isTrue();
		assertThat(repository.existsActiveByUserIdAndSessionId(1L, "expired-session")).isFalse();
		assertThat(repository.existsActiveByUserIdAndSessionId(2L, "active-session")).isFalse();
	}

	@Test
	void returnsOnlyUnexpiredSubscriptionsForTheRequestedUser() {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		upsert(input(1L, "session-null", endpoint("no-expiry"), "key", "auth", null));
		upsert(input(1L, "session-future", endpoint("future"), "key", "auth", now.plusDays(1)));
		upsert(input(1L, "session-expired", endpoint("expired"), "key", "auth", now.minusDays(1)));
		upsert(input(2L, "session-other", endpoint("other-user"), "key", "auth", null));

		assertThat(repository.findActiveByUserId(1L))
			.extracting(WebPushSubscription::endpoint)
			.containsExactly(endpoint("no-expiry"), endpoint("future"));
	}

	private WebPushSubscriptionInput input(
		long userId,
		String sessionId,
		String endpoint,
		String p256dh,
		String authSecret,
		OffsetDateTime expiresAt
	) {
		return new WebPushSubscriptionInput(userId, sessionId, endpoint, p256dh, authSecret, expiresAt);
	}

	private WebPushSubscription upsert(WebPushSubscriptionInput input) {
		return transaction.execute(status -> repository.upsert(input));
	}

	private WebPushSubscription concurrentUpsert(
		CountDownLatch ready,
		CountDownLatch start,
		WebPushSubscriptionInput input
	) throws InterruptedException {
		ready.countDown();
		start.await();
		return upsert(input);
	}

	private void installFailureAfterCleanupTrigger() {
		jdbc.sql("""
			CREATE FUNCTION reject_forced_web_push_registration()
			RETURNS TRIGGER AS $$
			BEGIN
			    IF NEW.endpoint = 'https://push.example/subscriptions/forced-failure'
			       AND NOT EXISTS (
			           SELECT 1
			           FROM web_push_subscriptions
			           WHERE session_id = NEW.session_id
			             AND endpoint_hash <> NEW.endpoint_hash
			       ) THEN
			        RAISE EXCEPTION 'forced registration failure';
			    END IF;
			    RETURN NEW;
			END;
			$$ LANGUAGE plpgsql
			""").update();
		jdbc.sql("""
			CREATE TRIGGER trg_reject_forced_web_push_registration
			BEFORE INSERT ON web_push_subscriptions
			FOR EACH ROW EXECUTE FUNCTION reject_forced_web_push_registration()
			""").update();
	}

	private void insertUser(long userId, String email, String nickname) {
		jdbc.sql("""
			INSERT INTO users (user_id, email, password_hash, nickname, email_verified)
			VALUES (:userId, :email, 'hash', :nickname, true)
			""")
			.param("userId", userId)
			.param("email", email)
			.param("nickname", nickname)
			.update();
	}

	private String endpointHash(long subscriptionId) {
		return jdbc.sql("""
			SELECT endpoint_hash
			FROM web_push_subscriptions
			WHERE subscription_id = :subscriptionId
			""")
			.param("subscriptionId", subscriptionId)
			.query(String.class)
			.single()
			.trim();
	}

	private int subscriptionCount() {
		return jdbc.sql("SELECT count(*)::integer FROM web_push_subscriptions")
			.query(Integer.class)
			.single();
	}

	private int sessionSubscriptionCount(String sessionId) {
		return jdbc.sql("""
			SELECT count(*)::integer
			FROM web_push_subscriptions
			WHERE session_id = :sessionId
			""")
			.param("sessionId", sessionId)
			.query(Integer.class)
			.single();
	}

	private static String endpoint(String suffix) {
		return "https://push.example/subscriptions/" + suffix;
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
