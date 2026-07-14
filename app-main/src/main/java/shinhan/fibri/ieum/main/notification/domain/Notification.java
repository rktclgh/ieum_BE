package shinhan.fibri.ieum.main.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.SourceType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

@Entity
@Table(name = "notifications")
public class Notification {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "notification_id")
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(nullable = false, columnDefinition = "notification_type")
	private NotificationType type;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(columnDefinition = "text")
	private String body;

	@Column(name = "ref_id")
	private Long refId;

	@Column(name = "answer_is_ai")
	private Boolean answerIsAi;

	@Column(name = "event_key", length = 120)
	private String eventKey;

	@Column(name = "is_read", nullable = false)
	private boolean read;

	@CreationTimestamp(source = SourceType.DB)
	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	protected Notification() {
	}

	private Notification(
		Long userId,
		NotificationType type,
		String title,
		String body,
		Long refId,
		Boolean answerIsAi
	) {
		this.userId = Objects.requireNonNull(userId, "userId must not be null");
		this.type = Objects.requireNonNull(type, "type must not be null");
		this.title = Objects.requireNonNull(title, "title must not be null");
		this.body = body;
		this.refId = refId;
		this.answerIsAi = answerIsAi;
		this.read = false;
	}

	public static Notification of(Long userId, NotificationType type, String title, String body, Long refId) {
		return of(userId, type, title, body, refId, null);
	}

	public static Notification of(
		Long userId,
		NotificationType type,
		String title,
		String body,
		Long refId,
		Boolean answerIsAi
	) {
		return new Notification(userId, type, title, body, refId, answerIsAi);
	}

	public Long getId() {
		return id;
	}

	public Long getUserId() {
		return userId;
	}

	public NotificationType getType() {
		return type;
	}

	public String getTitle() {
		return title;
	}

	public String getBody() {
		return body;
	}

	public Long getRefId() {
		return refId;
	}

	public Boolean getAnswerIsAi() {
		return answerIsAi;
	}

	public String getEventKey() {
		return eventKey;
	}

	public boolean isRead() {
		return read;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
