package shinhan.fibri.ieum.common.chat.domain;

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
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;

@Entity
@Table(name = "chat_rooms")
public class ChatRoom {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "room_id")
	private Long id;

	@Enumerated(EnumType.STRING)
	@JdbcType(PostgreSQLEnumJdbcType.class)
	@Column(name = "room_type", nullable = false, columnDefinition = "varchar(30)")
	private RoomType roomType;

	@Column(name = "meeting_id")
	private Long meetingId;

	@Column(name = "question_id")
	private Long questionId;

	@Column(name = "room_key", length = 80, unique = true)
	private String roomKey;

	@Column(name = "created_at", nullable = false, updatable = false)
	private OffsetDateTime createdAt;

	protected ChatRoom() {
	}

	private ChatRoom(RoomType roomType, Long meetingId, Long questionId, String roomKey) {
		this.roomType = Objects.requireNonNull(roomType, "roomType must not be null");
		this.meetingId = meetingId;
		this.questionId = questionId;
		this.roomKey = roomKey;
		this.createdAt = OffsetDateTime.now();
	}

	public static ChatRoom direct(Long firstUserId, Long secondUserId) {
		return new ChatRoom(RoomType.direct, null, null, directRoomKey(firstUserId, secondUserId));
	}

	public static ChatRoom group(Long meetingId) {
		return new ChatRoom(RoomType.group, Objects.requireNonNull(meetingId, "meetingId must not be null"), null, null);
	}

	public static ChatRoom question(Long questionId, Long firstUserId, Long secondUserId) {
		Objects.requireNonNull(questionId, "questionId must not be null");
		return new ChatRoom(RoomType.question, null, questionId, questionRoomKey(questionId, firstUserId, secondUserId));
	}

	public static String directRoomKey(Long firstUserId, Long secondUserId) {
		Objects.requireNonNull(firstUserId, "firstUserId must not be null");
		Objects.requireNonNull(secondUserId, "secondUserId must not be null");
		if (firstUserId.equals(secondUserId)) {
			throw new IllegalArgumentException("direct room participants must be different");
		}
		long min = Math.min(firstUserId, secondUserId);
		long max = Math.max(firstUserId, secondUserId);
		return "d:%d:%d".formatted(min, max);
	}

	public static String questionRoomKey(Long questionId, Long firstUserId, Long secondUserId) {
		Objects.requireNonNull(questionId, "questionId must not be null");
		Objects.requireNonNull(firstUserId, "firstUserId must not be null");
		Objects.requireNonNull(secondUserId, "secondUserId must not be null");
		if (firstUserId.equals(secondUserId)) {
			throw new IllegalArgumentException("question room participants must be different");
		}
		long min = Math.min(firstUserId, secondUserId);
		long max = Math.max(firstUserId, secondUserId);
		return "q:%d:%d:%d".formatted(questionId, min, max);
	}

	public Long getId() {
		return id;
	}

	public RoomType getRoomType() {
		return roomType;
	}

	public Long getMeetingId() {
		return meetingId;
	}

	public Long getQuestionId() {
		return questionId;
	}

	public String getRoomKey() {
		return roomKey;
	}

	public OffsetDateTime getCreatedAt() {
		return createdAt;
	}
}
