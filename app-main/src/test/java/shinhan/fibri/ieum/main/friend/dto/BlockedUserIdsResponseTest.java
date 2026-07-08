package shinhan.fibri.ieum.main.friend.dto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BlockedUserIdsResponseTest {

	@Test
	void fromCopiesUserIds() {
		List<Long> userIds = new ArrayList<>(List.of(77L, 88L));

		BlockedUserIdsResponse response = BlockedUserIdsResponse.from(userIds);
		userIds.add(99L);

		assertThat(response.userIds()).containsExactly(77L, 88L);
	}

	@Test
	void userIdsAreImmutable() {
		BlockedUserIdsResponse response = BlockedUserIdsResponse.from(List.of(77L));

		assertThatThrownBy(() -> response.userIds().add(88L))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
