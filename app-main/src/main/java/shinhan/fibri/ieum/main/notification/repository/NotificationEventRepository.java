package shinhan.fibri.ieum.main.notification.repository;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;

@Repository
public class NotificationEventRepository {

	private static final String INSERT_ONCE_SQL = """
		INSERT INTO notifications (
			user_id,
			type,
			title,
			body,
			ref_id,
			answer_is_ai,
			event_key
		)
		VALUES (?, CAST(? AS notification_type), ?, ?, ?, ?, ?)
		ON CONFLICT (user_id, event_key) WHERE event_key IS NOT NULL DO NOTHING
		RETURNING notification_id, created_at
		""";

	private final JdbcTemplate jdbcTemplate;

	public NotificationEventRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<InsertedNotification> insertOnce(
		Long userId,
		NotificationType type,
		String title,
		String body,
		Long refId,
		Boolean answerIsAi,
		String eventKey
	) {
		requireEventKey(eventKey);
		return jdbcTemplate.query(
			INSERT_ONCE_SQL,
			statement -> {
				statement.setObject(1, Objects.requireNonNull(userId, "userId must not be null"), Types.BIGINT);
				statement.setString(2, Objects.requireNonNull(type, "type must not be null").name());
				statement.setString(3, Objects.requireNonNull(title, "title must not be null"));
				statement.setString(4, body);
				statement.setObject(5, refId, Types.BIGINT);
				statement.setObject(6, answerIsAi, Types.BOOLEAN);
				statement.setString(7, eventKey);
			},
			resultSet -> resultSet.next()
				? Optional.of(new InsertedNotification(
					resultSet.getLong("notification_id"),
					resultSet.getObject("created_at", OffsetDateTime.class)
				))
				: Optional.empty()
		);
	}

	private static void requireEventKey(String eventKey) {
		if (eventKey == null || eventKey.isBlank() || eventKey.length() > 120) {
			throw new IllegalArgumentException("eventKey must contain between 1 and 120 characters");
		}
	}

	public record InsertedNotification(Long notificationId, OffsetDateTime createdAt) {
		public InsertedNotification {
			Objects.requireNonNull(notificationId, "notificationId must not be null");
			Objects.requireNonNull(createdAt, "createdAt must not be null");
		}
	}
}
