package shinhan.fibri.ieum.common.chat.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class ChatMemberId implements Serializable {

	@Column(name = "room_id")
	private Long roomId;

	@Column(name = "user_id")
	private Long userId;

	protected ChatMemberId() {
	}

	public ChatMemberId(Long roomId, Long userId) {
		this.roomId = roomId;
		this.userId = userId;
	}

	public Long getRoomId() {
		return roomId;
	}

	public Long getUserId() {
		return userId;
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ChatMemberId that)) {
			return false;
		}
		return Objects.equals(roomId, that.roomId) && Objects.equals(userId, that.userId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(roomId, userId);
	}
}
