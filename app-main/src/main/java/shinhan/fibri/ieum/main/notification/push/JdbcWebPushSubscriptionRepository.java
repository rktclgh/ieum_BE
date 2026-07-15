package shinhan.fibri.ieum.main.notification.push;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcWebPushSubscriptionRepository implements WebPushSubscriptionRepository {

	private final JdbcClient jdbc;

	public JdbcWebPushSubscriptionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public WebPushSubscription upsert(WebPushSubscriptionInput input) {
		Objects.requireNonNull(input, "input must not be null");
		String endpointHash = sha256(input.endpoint());
		jdbc.sql("LOCK TABLE web_push_subscriptions IN SHARE ROW EXCLUSIVE MODE")
			.update();
		jdbc.sql("""
			DELETE FROM web_push_subscriptions
			WHERE session_id = :sessionId
			  AND endpoint_hash <> :endpointHash
			""")
			.param("sessionId", input.sessionId())
			.param("endpointHash", endpointHash)
			.update();
		return jdbc.sql("""
			INSERT INTO web_push_subscriptions (
			    user_id,
			    session_id,
			    endpoint,
			    endpoint_hash,
			    p256dh,
			    auth_secret,
			    expires_at
			)
			VALUES (
			    :userId,
			    :sessionId,
			    :endpoint,
			    :endpointHash,
			    :p256dh,
			    :authSecret,
			    :expiresAt
			)
			ON CONFLICT (endpoint_hash) DO UPDATE
			SET user_id = EXCLUDED.user_id,
			    session_id = EXCLUDED.session_id,
			    endpoint = EXCLUDED.endpoint,
			    p256dh = EXCLUDED.p256dh,
			    auth_secret = EXCLUDED.auth_secret,
			    expires_at = EXCLUDED.expires_at,
			    binding_version = CASE
			        WHEN web_push_subscriptions.user_id = EXCLUDED.user_id
			         AND web_push_subscriptions.session_id = EXCLUDED.session_id
			        THEN web_push_subscriptions.binding_version
			        ELSE web_push_subscriptions.binding_version + 1
			    END
			RETURNING subscription_id,
			          user_id,
			          session_id,
			          endpoint,
			          p256dh,
			          auth_secret,
			          binding_version,
			          expires_at,
			          created_at,
			          updated_at
			""")
			.param("userId", input.userId())
			.param("sessionId", input.sessionId())
			.param("endpoint", input.endpoint())
			.param("endpointHash", endpointHash)
			.param("p256dh", input.p256dh())
			.param("authSecret", input.authSecret())
			.param("expiresAt", input.expiresAt(), Types.TIMESTAMP_WITH_TIMEZONE)
			.query(JdbcWebPushSubscriptionRepository::mapSubscription)
			.single();
	}

	@Override
	public boolean deleteByIdAndBindingVersion(long subscriptionId, long bindingVersion) {
		requirePositive(subscriptionId, "subscriptionId");
		requirePositive(bindingVersion, "bindingVersion");
		return jdbc.sql("""
			DELETE FROM web_push_subscriptions
			WHERE subscription_id = :subscriptionId
			  AND binding_version = :bindingVersion
			""")
			.param("subscriptionId", subscriptionId)
			.param("bindingVersion", bindingVersion)
			.update() == 1;
	}

	@Override
	public int deleteAllBySessionId(String sessionId) {
		requireSessionId(sessionId);
		return jdbc.sql("DELETE FROM web_push_subscriptions WHERE session_id = :sessionId")
			.param("sessionId", sessionId)
			.update();
	}

	@Override
	public int deleteAllByUserId(long userId) {
		requirePositive(userId, "userId");
		return jdbc.sql("DELETE FROM web_push_subscriptions WHERE user_id = :userId")
			.param("userId", userId)
			.update();
	}

	@Override
	public boolean existsActiveByUserIdAndSessionId(long userId, String sessionId) {
		requirePositive(userId, "userId");
		requireSessionId(sessionId);
		return Boolean.TRUE.equals(jdbc.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM web_push_subscriptions
			    WHERE user_id = :userId
			      AND session_id = :sessionId
			      AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
			)
			""")
			.param("userId", userId)
			.param("sessionId", sessionId)
			.query(Boolean.class)
			.single());
	}

	@Override
	public List<WebPushSubscription> findActiveByUserId(long userId) {
		requirePositive(userId, "userId");
		return jdbc.sql("""
			SELECT subscription_id,
			       user_id,
			       session_id,
			       endpoint,
			       p256dh,
			       auth_secret,
			       binding_version,
			       expires_at,
			       created_at,
			       updated_at
			FROM web_push_subscriptions
			WHERE user_id = :userId
			  AND (expires_at IS NULL OR expires_at > CURRENT_TIMESTAMP)
			ORDER BY subscription_id
			""")
			.param("userId", userId)
			.query(JdbcWebPushSubscriptionRepository::mapSubscription)
			.list();
	}

	private static WebPushSubscription mapSubscription(ResultSet resultSet, int rowNumber) throws SQLException {
		return new WebPushSubscription(
			resultSet.getLong("subscription_id"),
			resultSet.getLong("user_id"),
			resultSet.getString("session_id"),
			resultSet.getString("endpoint"),
			resultSet.getString("p256dh"),
			resultSet.getString("auth_secret"),
			resultSet.getLong("binding_version"),
			resultSet.getObject("expires_at", OffsetDateTime.class),
			resultSet.getObject("created_at", OffsetDateTime.class),
			resultSet.getObject("updated_at", OffsetDateTime.class)
		);
	}

	private static String sha256(String endpoint) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(endpoint.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is not available", exception);
		}
	}

	private static void requirePositive(long value, String fieldName) {
		if (value < 1) {
			throw new IllegalArgumentException(fieldName + " must be positive");
		}
	}

	private static void requireSessionId(String sessionId) {
		if (sessionId == null || sessionId.isBlank() || sessionId.length() > 64) {
			throw new IllegalArgumentException("sessionId must contain between 1 and 64 characters");
		}
	}
}
