package shinhan.fibri.ieum.main.notification.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * 구조 회귀 — 알림 i18n이 조용히 되돌아가는 두 경로를 잠근다.
 *
 * <p>둘 다 컴파일은 통과하지만 런타임에 잘못된 언어를 내보내는 종류의 회귀라
 * 일반 단위 테스트로는 안 잡힌다. 소스를 직접 스캔한다.
 */
class NotificationI18nStructureTest {

	private static final Path NOTIFICATION_PACKAGE =
		Path.of("src/main/java/shinhan/fibri/ieum/main/notification");

	/** 문구를 발행하는 프로듀서들. ko 폴백 렌더러는 한국어를 갖는 게 정상이라 제외한다. */
	private static final List<Path> PRODUCERS = List.of(
		Path.of("src/main/java/shinhan/fibri/ieum/main/answer/service/AnswerService.java"),
		Path.of("src/main/java/shinhan/fibri/ieum/main/friend/service/NotificationFriendRequestNotifier.java"),
		Path.of("src/main/java/shinhan/fibri/ieum/main/notification/internal/AiQuestionAnswerCompletionService.java"),
		Path.of("src/main/java/shinhan/fibri/ieum/main/notification/presence/RadiusNotificationListener.java")
	);

	private static final List<String> MIGRATED_COPY = List.of(
		"새 답변",
		"답변 채택",
		"친구 요청을 보냈어요",
		"주변 새 질문",
		"주변 새 모임",
		"회원님의 질문에 답변이 달렸어요",
		"회원님의 답변이 채택됐어요"
	);

	/**
	 * ambient 로케일 금지. {@code LocaleContextHolder}는 <b>요청을 보낸 사람</b>의 Accept-Language를 집는데,
	 * 알림은 발송자 ≠ 수신자라 항상 틀린 사람의 언어가 된다(답변 채택: 질문자가 요청 → 답변자가 수신).
	 * ephemeral 반경 알림과 AI callback은 HTTP 요청 컨텍스트조차 없다.
	 */
	@Test
	void notificationPackageNeverResolvesAmbientLocale() throws IOException {
		List<String> offenders = new ArrayList<>();

		try (Stream<Path> sources = Files.walk(NOTIFICATION_PACKAGE)) {
			for (Path source : sources.filter(path -> path.toString().endsWith(".java")).toList()) {
				String content = Files.readString(source, StandardCharsets.UTF_8);
				if (content.contains("LocaleContextHolder") || content.contains("MessageSource")) {
					offenders.add(source.toString());
				}
			}
		}

		assertThat(offenders)
			.as("알림은 수신자의 user_settings.language로만 렌더해야 한다. ambient 로케일 사용 금지")
			.isEmpty();
	}

	/** 프로듀서에 한국어 문구가 되돌아오면 그 알림만 조용히 번역 불가 상태가 된다. */
	@Test
	void producersCarryNoHardcodedKoreanCopy() throws IOException {
		List<String> offenders = new ArrayList<>();

		for (Path producer : PRODUCERS) {
			String content = Files.readString(producer, StandardCharsets.UTF_8);
			for (String copy : MIGRATED_COPY) {
				if (content.contains(copy)) {
					offenders.add("%s → \"%s\"".formatted(producer.getFileName(), copy));
				}
			}
		}

		assertThat(offenders)
			.as("문구는 NotificationMessageKey 상수로만 발행한다. 프론트 카탈로그가 번역을 소유한다")
			.isEmpty();
	}

	/** 스캔 대상 경로가 실제로 존재해야 테스트가 의미를 갖는다(리팩터링으로 경로가 바뀌면 조용히 통과하는 것 방지). */
	@Test
	void scannedPathsExist() {
		assertThat(NOTIFICATION_PACKAGE).exists();
		assertThat(PRODUCERS).allSatisfy(producer -> assertThat(producer).exists());
	}
}
