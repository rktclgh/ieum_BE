package shinhan.fibri.ieum.ai.question.generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalAnswerPromptTest {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@Test
	void serializesOnlyQuestionCoarseKoreanRegionAndEphemeralEvidenceFields() throws Exception {
		LocalAnswerPrompt prompt = prompt(List.of(
			new LocalAnswerEvidence(0, "버스 이용 안내", "앞문으로 승차하고 뒷문으로 하차합니다.", "curated_kg"),
			new LocalAnswerEvidence(1, "서울 교통 안내", "교통카드를 단말기에 접촉합니다.", "government")
		));
		LocalAnswerModelPrompt modelPrompt = new LocalAnswerPromptFactory(OBJECT_MAPPER).create(prompt);

		JsonNode payload = OBJECT_MAPPER.readTree(modelPrompt.userInstruction());
		assertThat(fieldNames(payload)).containsExactlyInAnyOrder("question", "coarseRegion", "evidence");
		assertThat(fieldNames(payload.get("question"))).containsExactlyInAnyOrder("title", "content");
		assertThat(fieldNames(payload.get("coarseRegion")))
			.containsExactlyInAnyOrder("country", "sido", "sigungu", "eupMyeonDong");
		assertThat(payload.get("evidence").size()).isEqualTo(2);
		assertThat(fieldNames(payload.get("evidence").get(0)))
			.containsExactlyInAnyOrder("evidenceIndex", "title", "excerpt", "sourceType");
		assertThat(modelPrompt.userInstruction()).doesNotContain(
			"sourceId", "chunkId", "relationId", "userId", "authorId",
			"latitude", "longitude", "rawAddress", "detailAddress", "label", "place"
		);
		assertThat(modelPrompt.systemInstruction())
			.contains("untrusted", "only the supplied evidence", "Return JSON only")
			.contains("evidenceIndex", "startIndex", "endIndex")
			.contains("Java UTF-16 code-unit offsets", "end-exclusive", "surrogate pair");
	}

	@Test
	void omitsCoarseRegionWhenTheQuestionHasNoStoredRegion() throws Exception {
		LocalAnswerPrompt prompt = new LocalAnswerPrompt(
			"질문",
			"내용",
			LocalAnswerRegion.empty(),
			List.of(new LocalAnswerEvidence(0, "근거", "근거 내용", "curated_kg"))
		);

		JsonNode payload = OBJECT_MAPPER.readTree(new LocalAnswerPromptFactory(OBJECT_MAPPER)
			.create(prompt)
			.userInstruction());

		assertThat(payload.get("coarseRegion").isNull()).isTrue();
	}

	@Test
	void defensivelyCopiesEvidenceAndRequiresSequentialZeroBasedIndexes() {
		List<LocalAnswerEvidence> mutable = new ArrayList<>();
		mutable.add(new LocalAnswerEvidence(0, "근거", "근거 내용", "curated_kg"));
		LocalAnswerPrompt prompt = prompt(mutable);

		mutable.clear();

		assertThat(prompt.evidence()).hasSize(1);
		assertThatThrownBy(() -> prompt(List.of(
			new LocalAnswerEvidence(1, "근거", "근거 내용", "curated_kg")
		))).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("evidenceIndex");
	}

	@Test
	void acceptsOneToEightEvidenceItemsAndRejectsAnyOtherCount() {
		assertThatThrownBy(() -> prompt(List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1 to 8");

		List<LocalAnswerEvidence> nine = new ArrayList<>();
		for (int index = 0; index < 9; index++) {
			nine.add(new LocalAnswerEvidence(index, "근거 " + index, "내용 " + index, "curated_kg"));
		}
		assertThatThrownBy(() -> prompt(nine))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1 to 8");
	}

	@Test
	void rejectsNonKoreanOrOverPreciseRegionShape() {
		assertThatThrownBy(() -> new LocalAnswerRegion("US", "California", null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("KR");
		assertThat(LocalAnswerRegion.class.getRecordComponents())
			.extracting(component -> component.getName())
			.containsExactly("country", "sido", "sigungu", "eupMyeonDong");
	}

	private LocalAnswerPrompt prompt(List<LocalAnswerEvidence> evidence) {
		return new LocalAnswerPrompt(
			"버스는 어떻게 타나요?",
			"한국 버스 승하차 방법을 알고 싶습니다.",
			LocalAnswerRegion.korea("서울특별시", "중구", "태평로1가"),
			evidence
		);
	}

	private static List<String> fieldNames(JsonNode node) {
		List<String> fields = new ArrayList<>();
		node.fieldNames().forEachRemaining(fields::add);
		return fields;
	}
}
