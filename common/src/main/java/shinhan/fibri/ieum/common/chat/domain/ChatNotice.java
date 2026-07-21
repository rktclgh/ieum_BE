package shinhan.fibri.ieum.common.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.Objects;
import shinhan.fibri.ieum.common.auth.domain.User;

@Entity
@Table(
	name = "chat_notices",
	uniqueConstraints = @UniqueConstraint(name = "uidx_chat_notices_room_message", columnNames = {"room_id", "message_id"})
)
public class ChatNotice {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "notice_id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "room_id", nullable = false)
	private ChatRoom room;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "message_id", nullable = false)
	private Message message;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "created_by")
	private User createdBy;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	protected ChatNotice() {
	}

	private ChatNotice(ChatRoom room, Message message, User createdBy, OffsetDateTime createdAt) {
		this.room = Objects.requireNonNull(room, "room must not be null");
		this.message = Objects.requireNonNull(message, "message must not be null");
		this.createdBy = createdBy;
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
	}

	public static ChatNotice create(ChatRoom room, Message message, User createdBy, OffsetDateTime createdAt) {
		return new ChatNotice(room, message, createdBy, createdAt);
	}

	public Long getId() {
		return id;
	}

	public ChatRoom getRoom() {
		return room;
	}

	public Message getMessage() {
		return message;
	}

	public User getCreatedBy() {
		return createdBy;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
