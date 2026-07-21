package shinhan.fibri.ieum.main.notification.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class KoreanNotificationMessageFallbackTest {

	private final KoreanNotificationMessageFallback fallback = new KoreanNotificationMessageFallback();

	@Test
	void rendersAnswerCreated() {
		NotificationMessage message = NotificationMessage.of(NotificationMessageKey.ANSWER_CREATED);

		assertThat(fallback.renderTitle(message)).isEqualTo("새 답변");
		assertThat(fallback.renderBody(message)).isEqualTo("회원님의 질문에 답변이 달렸어요");
	}

	@Test
	void rendersAnswerAccepted() {
		NotificationMessage message = NotificationMessage.of(NotificationMessageKey.ANSWER_ACCEPTED);

		assertThat(fallback.renderTitle(message)).isEqualTo("답변 채택");
		assertThat(fallback.renderBody(message)).isEqualTo("회원님의 답변이 채택됐어요");
	}

	@Test
	void rendersChatMessage() {
		NotificationMessage message = NotificationMessage.of(NotificationMessageKey.CHAT_MESSAGE);

		assertThat(fallback.renderTitle(message)).isEqualTo("새 메시지");
		assertThat(fallback.renderBody(message)).isEqualTo("새 채팅 메시지가 도착했어요");
	}

	@Test
	void substitutesNicknameInFriendRequest() {
		NotificationMessage message = NotificationMessage.of(
			NotificationMessageKey.FRIEND_REQUEST,
			Map.of("nickname", "철수")
		);

		assertThat(fallback.renderTitle(message)).isEqualTo("친구 요청");
		assertThat(fallback.renderBody(message)).isEqualTo("철수님이 친구 요청을 보냈어요");
	}

	@Test
	void passesRadiusSubjectThroughUntranslated() {
		NotificationMessage question = NotificationMessage.of(
			NotificationMessageKey.RADIUS_QUESTION,
			Map.of("subject", "이 근처 맛집 아시는 분?")
		);
		NotificationMessage meeting = NotificationMessage.of(
			NotificationMessageKey.RADIUS_MEETING,
			Map.of("subject", "토요일 러닝 같이 해요")
		);

		assertThat(fallback.renderTitle(question)).isEqualTo("주변 새 질문");
		assertThat(fallback.renderBody(question)).isEqualTo("이 근처 맛집 아시는 분?");
		assertThat(fallback.renderTitle(meeting)).isEqualTo("주변 새 모임");
		assertThat(fallback.renderBody(meeting)).isEqualTo("토요일 러닝 같이 해요");
	}

	@Test
	void unknownKeyDegradesWithoutThrowing() {
		NotificationMessage message = NotificationMessage.of("notification.does.not.exist");

		assertThat(fallback.renderTitle(message)).isEqualTo("notification.does.not.exist");
		assertThat(fallback.renderBody(message)).isNull();
	}

	@Test
	void missingParamLeavesPlaceholderIntactInsteadOfThrowing() {
		NotificationMessage message = NotificationMessage.of(NotificationMessageKey.FRIEND_REQUEST);

		assertThat(fallback.renderBody(message)).isEqualTo("{nickname}님이 친구 요청을 보냈어요");
	}

	@Test
	void coversEveryRegisteredKey() {
		for (String key : NotificationMessageKey.all()) {
			NotificationMessage message = NotificationMessage.of(key);

			assertThat(fallback.renderTitle(message))
				.as("ko title missing for %s", key)
				.isNotEqualTo(key);
		}
	}
}
