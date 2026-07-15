package shinhan.fibri.ieum.main.chat.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record ChatRoomListChangeEvent(
	Type type,
	Long roomId,
	List<Long> userIds
) {

	public enum Type {
		UPSERT,
		REMOVE
	}

	public ChatRoomListChangeEvent {
		Objects.requireNonNull(type, "type must not be null");
		Objects.requireNonNull(roomId, "roomId must not be null");
		userIds = normalize(userIds);
	}

	public static ChatRoomListChangeEvent upsert(Long roomId, Collection<Long> userIds) {
		return new ChatRoomListChangeEvent(Type.UPSERT, roomId, normalize(userIds));
	}

	public static ChatRoomListChangeEvent remove(Long roomId, Collection<Long> userIds) {
		return new ChatRoomListChangeEvent(Type.REMOVE, roomId, normalize(userIds));
	}

	private static List<Long> normalize(Collection<Long> userIds) {
		if (userIds == null || userIds.isEmpty()) {
			return List.of();
		}
		return userIds.stream()
			.filter(Objects::nonNull)
			.collect(Collectors.collectingAndThen(
				Collectors.toCollection(LinkedHashSet::new),
				List::copyOf
			));
	}
}
