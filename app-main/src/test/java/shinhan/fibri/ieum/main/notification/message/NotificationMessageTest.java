package shinhan.fibri.ieum.main.notification.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NotificationMessageTest {

	@Test
	void createsMessageWithoutParams() {
		NotificationMessage message = NotificationMessage.of(NotificationMessageKey.ANSWER_ACCEPTED);

		assertThat(message.key()).isEqualTo("notification.answer.accepted");
		assertThat(message.params()).isEmpty();
	}

	@Test
	void nullParamsBecomeEmptyMap() {
		NotificationMessage message = new NotificationMessage(NotificationMessageKey.ANSWER_CREATED, null);

		assertThat(message.params()).isEmpty();
	}

	@Test
	void rejectsNullKey() {
		assertThatNullPointerException()
			.isThrownBy(() -> new NotificationMessage(null, Map.of()));
	}

	@Test
	void copiesParamsDefensivelySoLaterMutationCannotLeakIn() {
		Map<String, String> source = new HashMap<>();
		source.put("nickname", "철수");

		NotificationMessage message = NotificationMessage.of(NotificationMessageKey.FRIEND_REQUEST, source);
		source.put("nickname", "영희");

		assertThat(message.params()).containsExactly(Map.entry("nickname", "철수"));
	}

	@Test
	void exposesParamsAsImmutableMap() {
		NotificationMessage message = NotificationMessage.of(
			NotificationMessageKey.FRIEND_REQUEST,
			Map.of("nickname", "철수")
		);

		assertThatThrownBy(() -> message.params().put("nickname", "영희"))
			.isInstanceOf(UnsupportedOperationException.class);
	}
}
