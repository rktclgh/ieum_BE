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
import java.util.Map;
import java.util.Objects;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SourceType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.hibernate.type.SqlTypes;
import shinhan.fibri.ieum.main.notification.message.NotificationMessage;

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

	/**
	 * 사건 식별자. 프론트가 이 키로 자기 카탈로그에서 수신자 언어의 문구를 찾는다.
	 * 마이그레이션(v37) 이전 행은 {@code null}이고, 그 경우 클라이언트는 {@code title}/{@code body}로 폴백한다.
	 */
	@Column(name = "message_key", length = 100)
	private String messageKey;

	/** 발송 시점 스냅샷 값(예: {@code {"nickname":"철수"}}). 참조가 아니라 값이라 과거 알림이 소급 변경되지 않는다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "message_params", columnDefinition = "jsonb")
	private Map<String, String> messageParams;

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
		NotificationMessage message,
		String title,
		String body,
		Long refId,
		Boolean answerIsAi
	) {
		this.userId = Objects.requireNonNull(userId, "userId must not be null");
		this.type = Objects.requireNonNull(type, "type must not be null");
		this.title = Objects.requireNonNull(title, "title must not be null");
		this.body = body;
		this.messageKey = message == null ? null : message.key();
		this.messageParams = message == null || message.params().isEmpty() ? null : Map.copyOf(message.params());
		this.refId = refId;
		this.answerIsAi = answerIsAi;
		this.read = false;
	}

	/** 키 없는 레거시 형태. {@code message_key}가 null로 남아 클라이언트는 title/body로 렌더한다. */
	public static Notification of(Long userId, NotificationType type, String title, String body, Long refId) {
		return of(userId, type, title, body, refId, null);
	}

	/** 키 없는 레거시 형태. {@code message_key}가 null로 남아 클라이언트는 title/body로 렌더한다. */
	public static Notification of(
		Long userId,
		NotificationType type,
		String title,
		String body,
		Long refId,
		Boolean answerIsAi
	) {
		return new Notification(userId, type, null, title, body, refId, answerIsAi);
	}

	/**
	 * 현재 발행 경로. {@code title}/{@code body}는 ko 폴백이며 구클라이언트·NOT NULL 제약용으로 함께 기록한다
	 * (이중 기록 — notification/i18n/spec.md §2.3).
	 */
	public static Notification of(
		Long userId,
		NotificationType type,
		NotificationMessage message,
		String title,
		String body,
		Long refId,
		Boolean answerIsAi
	) {
		return new Notification(
			userId,
			type,
			Objects.requireNonNull(message, "message must not be null"),
			title,
			body,
			refId,
			answerIsAi
		);
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

	public String getMessageKey() {
		return messageKey;
	}

	public Map<String, String> getMessageParams() {
		return messageParams == null ? Map.of() : Map.copyOf(messageParams);
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
