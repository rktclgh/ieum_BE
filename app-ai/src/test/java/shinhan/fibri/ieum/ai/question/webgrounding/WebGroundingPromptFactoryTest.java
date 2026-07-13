package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.analysis.QuestionInputSnapshot;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;
import shinhan.fibri.ieum.ai.question.analysis.StoredLocationSnapshot;

class WebGroundingPromptFactoryTest {

	private final WebGroundingPromptFactory factory = new WebGroundingPromptFactory(
		new WebQuestionPiiSanitizer()
	);

	@Test
	void createsOnlySanitizedQuestionAndCoarseRegionFields() {
		QuestionInputSnapshot snapshot = snapshot(
			"우리집에서 버스 타는 법을 user@example.com으로 알려주세요",
			"대한민국 서울특별시 중구 태평로1가 31, 101동 202호, 010-1234-5678, "
				+ "37.5665,126.9780 대신 일반적인 승하차 방법을 알려주세요."
		);

		WebGroundingPrompt prompt = factory.create(snapshot, coarseRegion()).orElseThrow();

		assertThat(recordComponents(WebGroundingPrompt.class))
			.containsExactly("title", "content", "coarseRegion");
		assertThat(recordComponents(WebGroundingRegion.class))
			.containsExactly("country", "sido", "sigungu");
		assertThat(prompt.title())
			.contains("[REDACTED]", "버스 타는 법")
			.doesNotContain("우리집", "user@example.com");
		assertThat(prompt.content())
			.contains("[REDACTED]", "일반적인 승하차 방법")
			.doesNotContain(
				"대한민국 서울특별시 중구 태평로1가 31",
				"101동 202호",
				"010-1234-5678",
				"37.5665",
				"126.9780"
			);
		assertThat(prompt.coarseRegion())
			.isEqualTo(WebGroundingRegion.korea("서울특별시", "중구"));
	}

	@Test
	void returnsEmptyWhenRedactionLeavesNoMeaningfulQuestionText() {
		QuestionInputSnapshot snapshot = snapshot(
			"user@example.com",
			"010-1234-5678 / 900101-1234567 / 37.5665,126.9780"
		);

		assertThat(factory.create(snapshot, coarseRegion())).isEmpty();
	}

	@Test
	void preservesAUsableQuestionWithASafePlaceholderWhenOnlyOneFieldLosesMeaning() {
		WebGroundingPrompt redactedTitle = factory.create(
			snapshot("--- user@example.com ---", "버스 승하차 방법을 알려주세요"),
			coarseRegion()
		).orElseThrow();
		WebGroundingPrompt redactedContent = factory.create(
			snapshot("버스 승하차 방법", "010-1234-5678 / 37.5665,126.9780"),
			coarseRegion()
		).orElseThrow();

		assertThat(redactedTitle.title()).isEqualTo("[REDACTED]");
		assertThat(redactedTitle.content()).isEqualTo("버스 승하차 방법을 알려주세요");
		assertThat(redactedContent.title()).isEqualTo("버스 승하차 방법");
		assertThat(redactedContent.content()).isEqualTo("[REDACTED]");
	}

	@Test
	void rejectsInvalidBoundaryValues() {
		assertThatThrownBy(() -> factory.create(null, coarseRegion()))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("snapshot");
		assertThatThrownBy(() -> factory.create(snapshot("질문", "내용"), null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("coarseRegion");
		assertThatThrownBy(() -> new WebGroundingRegion("US", null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("country");
		assertThatThrownBy(() -> new WebGroundingRegion(null, "서울특별시", null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("country");
		assertThatThrownBy(() -> new WebGroundingPrompt(" ", "내용", WebGroundingRegion.empty()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("title");
	}

	private QuestionInputSnapshot snapshot(String title, String content) {
		return new QuestionInputSnapshot(
			title,
			content,
			new StoredLocationSnapshot(
				37.5665d,
				126.978d,
				"대한민국 서울특별시 중구 태평로1가 31",
				"101동 202호",
				"우리집"
			)
		);
	}

	private RegionContext coarseRegion() {
		return RegionContext.korea("서울특별시", "중구", "태평로1가", null);
	}

	private static List<String> recordComponents(Class<?> type) {
		return Arrays.stream(type.getRecordComponents())
			.map(component -> component.getName())
			.toList();
	}
}
