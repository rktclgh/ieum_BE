package shinhan.fibri.ieum.common.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import shinhan.fibri.ieum.common.auth.domain.User;

@Entity
@Table(name = "messages")
public class Message {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "message_id")
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "room_id", nullable = false)
	private ChatRoom room;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "sender_id", nullable = false)
	private User sender;

	@Column(columnDefinition = "text")
	private String content;

	@Column(name = "image_file_id")
	private UUID imageFileId;

	@Enumerated(EnumType.STRING)
	@Column(name = "message_type", nullable = false, length = 16)
	private MessageType messageType;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	@Column(name = "deleted_at")
	private OffsetDateTime deletedAt;

	protected Message() {
	}

	private Message(
		ChatRoom room,
		User sender,
		String content,
		UUID imageFileId,
		MessageType messageType,
		OffsetDateTime createdAt
	) {
		if (content == null && imageFileId == null) {
			throw new IllegalArgumentException("content or imageFileId is required");
		}
		this.room = Objects.requireNonNull(room, "room must not be null");
		this.sender = Objects.requireNonNull(sender, "sender must not be null");
		this.content = content;
		this.imageFileId = imageFileId;
		this.messageType = Objects.requireNonNull(messageType, "messageType must not be null");
		this.createdAt = Objects.requireNonNull(createdAt, "createdAt must not be null");
	}

	public static Message text(ChatRoom room, User sender, String content) {
		return text(room, sender, content, OffsetDateTime.now());
	}

	public static Message text(ChatRoom room, User sender, String content, OffsetDateTime createdAt) {
		return new Message(
			room,
			sender,
			Objects.requireNonNull(content, "content must not be null"),
			null,
			MessageType.user,
			createdAt
		);
	}

	public static Message image(ChatRoom room, User sender, UUID imageFileId) {
		return image(room, sender, imageFileId, OffsetDateTime.now());
	}

	public static Message image(ChatRoom room, User sender, UUID imageFileId, OffsetDateTime createdAt) {
		return new Message(
			room,
			sender,
			null,
			Objects.requireNonNull(imageFileId, "imageFileId must not be null"),
			MessageType.user,
			createdAt
		);
	}

	public static Message system(
		ChatRoom room,
		User sender,
		String content,
		OffsetDateTime createdAt
	) {
		if (content == null || content.isBlank()) {
			throw new IllegalArgumentException("system content must not be blank");
		}
		return new Message(room, sender, content, null, MessageType.system, createdAt);
	}

	public void markDeleted(OffsetDateTime deletedAt) {
		this.deletedAt = Objects.requireNonNull(deletedAt, "deletedAt must not be null");
	}

	public Long getId() {
		return id;
	}

	public ChatRoom getRoom() {
		return room;
	}

	public User getSender() {
		return sender;
	}

	public String getContent() {
		return content;
	}

	public UUID getImageFileId() {
		return imageFileId;
	}

	public MessageType getMessageType() {
		return messageType;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}

	public OffsetDateTime getDeletedAt() {
		return deletedAt;
	}
}
