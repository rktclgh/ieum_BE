package shinhan.fibri.ieum.main.friend.dto;

import java.util.List;

public record BlockedUserIdsResponse(
	List<Long> userIds
) {

	public BlockedUserIdsResponse {
		userIds = List.copyOf(userIds);
	}

	public static BlockedUserIdsResponse from(List<Long> userIds) {
		return new BlockedUserIdsResponse(userIds);
	}
}
