package shinhan.fibri.ieum.common.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import shinhan.fibri.ieum.common.auth.domain.User;

@Entity
@Table(name = "chat_members")
public class ChatMember {

	@EmbeddedId
	private ChatMemberId id;

	@MapsId("roomId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "room_id", nullable = false)
	private ChatRoom room;

	@MapsId("userId")
	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "user_id", nullable = false)
	private User user;

	@Column(name = "joined_at", nullable = false)
	private OffsetDateTime joinedAt;

	@Column(name = "left_at")
	private OffsetDateTime leftAt;

	@Column(name = "last_read_at")
	private OffsetDateTime lastReadAt;

	@Column(name = "pinned_at")
	private OffsetDateTime pinnedAt;

	@Column(name = "notify_enabled", nullable = false)
	private boolean notifyEnabled;

	protected ChatMember() {
	}

	private ChatMember(ChatRoom room, User user) {
		this.room = Objects.requireNonNull(room, "room must not be null");
		this.user = Objects.requireNonNull(user, "user must not be null");
		this.id = new ChatMemberId(room.getId(), user.getId());
		this.joinedAt = OffsetDateTime.now();
		this.notifyEnabled = true;
	}

	public static ChatMember join(ChatRoom room, User user) {
		return new ChatMember(room, user);
	}

	public void leave(OffsetDateTime leftAt) {
		this.leftAt = Objects.requireNonNull(leftAt, "leftAt must not be null");
	}

	public void rejoin() {
		this.leftAt = null;
	}

	public void markRead(OffsetDateTime readAt) {
		this.lastReadAt = Objects.requireNonNull(readAt, "readAt must not be null");
	}

	public void setPinned(boolean pinned, OffsetDateTime now) {
		if (pinned) {
			this.pinnedAt = Objects.requireNonNull(now, "now must not be null");
			return;
		}
		this.pinnedAt = null;
	}

	public void setNotifyEnabled(boolean notifyEnabled) {
		this.notifyEnabled = notifyEnabled;
	}

	public boolean isActive() {
		return leftAt == null;
	}

	public ChatMemberId getId() {
		return id;
	}

	public ChatRoom getRoom() {
		return room;
	}

	public User getUser() {
		return user;
	}

	public OffsetDateTime getJoinedAt() {
		return joinedAt;
	}

	public OffsetDateTime getLeftAt() {
		return leftAt;
	}

	public OffsetDateTime getLastReadAt() {
		return lastReadAt;
	}

	public OffsetDateTime getPinnedAt() {
		return pinnedAt;
	}

	public boolean isNotifyEnabled() {
		return notifyEnabled;
	}
}
