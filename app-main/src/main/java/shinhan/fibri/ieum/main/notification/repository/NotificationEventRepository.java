package shinhan.fibri.ieum.main.notification.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.main.notification.domain.NotificationType;
import shinhan.fibri.ieum.main.notification.message.NotificationMessage;

@Repository
public class NotificationEventRepository {

	private static final String INSERT_ONCE_SQL = """
		INSERT INTO notifications (
			user_id,
			type,
			title,
			body,
			message_key,
			message_params,
			ref_id,
			answer_is_ai,
			event_key
		)
		SELECT ?, CAST(? AS notification_type), ?, ?, ?, CAST(? AS jsonb), ?, ?, ?
		WHERE NOT (
			COALESCE(?, FALSE)
			AND CAST(? AS notification_type) = 'question'::notification_type
			AND EXISTS (
				SELECT 1
				FROM notifications existing
				WHERE existing.user_id = ?
				  AND existing.type = 'question'::notification_type
				  AND existing.ref_id = ?
				  AND existing.answer_is_ai = TRUE
			)
		)
		ON CONFLICT (user_id, event_key) WHERE event_key IS NOT NULL DO NOTHING
		RETURNING notification_id, created_at
		""";

	private final JdbcTemplate jdbcTemplate;
	private final ObjectMapper objectMapper;

	public NotificationEventRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.objectMapper = objectMapper;
	}

	public Optional<InsertedNotification> insertOnce(
		Long userId,
		NotificationType type,
		NotificationMessage message,
		String title,
		String body,
		Long refId,
		Boolean answerIsAi,
		String eventKey
	) {
		requireEventKey(eventKey);
		Objects.requireNonNull(message, "message must not be null");
		String messageParams = serializeParams(message.params());
		return jdbcTemplate.query(
			INSERT_ONCE_SQL,
			statement -> {
				statement.setObject(1, Objects.requireNonNull(userId, "userId must not be null"), Types.BIGINT);
				statement.setString(2, Objects.requireNonNull(type, "type must not be null").name());
				statement.setString(3, Objects.requireNonNull(title, "title must not be null"));
				statement.setString(4, body);
				statement.setString(5, message.key());
				statement.setString(6, messageParams);
				statement.setObject(7, refId, Types.BIGINT);
				statement.setObject(8, answerIsAi, Types.BOOLEAN);
				statement.setString(9, eventKey);
				statement.setObject(10, answerIsAi, Types.BOOLEAN);
				statement.setString(11, type.name());
				statement.setObject(12, userId, Types.BIGINT);
				statement.setObject(13, refId, Types.BIGINT);
			},
			resultSet -> resultSet.next()
				? Optional.of(new InsertedNotification(
					resultSet.getLong("notification_id"),
					resultSet.getObject("created_at", OffsetDateTime.class)
				))
				: Optional.empty()
		);
	}

	/** 파라미터가 없으면 SQL NULL로 저장해 JPA 경로({@code Notification})와 표현을 맞춘다. */
	private String serializeParams(Map<String, String> params) {
		if (params.isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(params);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("failed to serialize notification message params", exception);
		}
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
