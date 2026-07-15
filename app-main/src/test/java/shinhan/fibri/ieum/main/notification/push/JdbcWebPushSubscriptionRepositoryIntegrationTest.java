package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class JdbcWebPushSubscriptionRepositoryIntegrationTest {

	private static final String DATABASE = "ieum_main_web_push_subscriptions";

	private JdbcClient jdbc;
	private WebPushSubscriptionRepository repository;

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		repository = new JdbcWebPushSubscriptionRepository(jdbc);
		insertUser(1L, "push-one@example.com", "push-one");
		insertUser(2L, "push-two@example.com", "push-two");
	}

	@Test
	void insertsVersionOneAndHashesTheEndpointServerSide() {
		String endpoint = "https://push.example/subscriptions/one";

		WebPushSubscription stored = repository.upsert(input(
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
		WebPushSubscription first = repository.upsert(input(
			1L, "session-1", endpoint, "old-key", "old-auth", null
		));
		OffsetDateTime expiry = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30);

		WebPushSubscription updated = repository.upsert(input(
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
		WebPushSubscription first = repository.upsert(input(
			1L, "session-1", endpoint, "key-1", "auth-1", null
		));

		WebPushSubscription rebound = repository.upsert(input(
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
		WebPushSubscription first = repository.upsert(input(
			1L, "session-1", endpoint, "key-1", "auth-1", null
		));
		WebPushSubscription rebound = repository.upsert(input(
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
	void deletesAllSubscriptionsBySessionAndByUser() {
		repository.upsert(input(1L, "session-a", endpoint("a1"), "key", "auth", null));
		repository.upsert(input(1L, "session-a", endpoint("a2"), "key", "auth", null));
		repository.upsert(input(1L, "session-b", endpoint("b"), "key", "auth", null));
		repository.upsert(input(2L, "session-c", endpoint("c"), "key", "auth", null));

		assertThat(repository.deleteAllBySessionId("session-a")).isEqualTo(2);
		assertThat(repository.deleteAllByUserId(1L)).isEqualTo(1);
		assertThat(repository.findActiveByUserId(1L)).isEmpty();
		assertThat(repository.findActiveByUserId(2L))
			.extracting(WebPushSubscription::endpoint)
			.containsExactly(endpoint("c"));
	}

	@Test
	void findsWhetherTheCurrentUserSessionHasAnActiveSubscription() {
		OffsetDateTime expired = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
		repository.upsert(input(1L, "active-session", endpoint("active"), "key", "auth", null));
		repository.upsert(input(1L, "expired-session", endpoint("expired"), "key", "auth", expired));

		assertThat(repository.existsActiveByUserIdAndSessionId(1L, "active-session")).isTrue();
		assertThat(repository.existsActiveByUserIdAndSessionId(1L, "expired-session")).isFalse();
		assertThat(repository.existsActiveByUserIdAndSessionId(2L, "active-session")).isFalse();
	}

	@Test
	void returnsOnlyUnexpiredSubscriptionsForTheRequestedUser() {
		OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
		repository.upsert(input(1L, "session-null", endpoint("no-expiry"), "key", "auth", null));
		repository.upsert(input(1L, "session-future", endpoint("future"), "key", "auth", now.plusDays(1)));
		repository.upsert(input(1L, "session-expired", endpoint("expired"), "key", "auth", now.minusDays(1)));
		repository.upsert(input(2L, "session-other", endpoint("other-user"), "key", "auth", null));

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
