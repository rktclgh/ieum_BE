package shinhan.fibri.ieum.ai.knowledge.accepted;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;
import shinhan.fibri.ieum.ai.question.analysis.StoredAddressRegionParser;
import shinhan.fibri.ieum.ai.question.analysis.StoredLocationSnapshot;
import shinhan.fibri.ieum.ai.question.webgrounding.WebQuestionPiiSanitizer;

class AcceptedAnswerKnowledgeDocumentFactoryTest {

	private final AcceptedAnswerKnowledgeDocumentFactory factory =
		new AcceptedAnswerKnowledgeDocumentFactory(
			new WebQuestionPiiSanitizer(),
			new StoredAddressRegionParser()
		);
	private final StoredLocationSnapshot seoulLocation = new StoredLocationSnapshot(
		37.5665d,
		126.978d,
		"대한민국 서울특별시 중구 태평로1가 31",
		"101동 202호",
		"우리집"
	);

	@Test
	void buildsTheExactNormalizedCanonicalChunkAndHashesOnlyThatChunk() {
		AcceptedAnswerKnowledgeSnapshot snapshot = new AcceptedAnswerKnowledgeSnapshot(
			"\tＡＩ   교통 문의\n",
			"\t버스\n  이용  시간은? ",
			"\n  2번 버스를   타고\n시청역에서 내리세요.  ",
			seoulLocation,
			GeoScope.local
		);
		String expectedChunk = String.join("\n",
			"질문 제목: AI 교통 문의",
			"질문 내용: 버스 이용 시간은?",
			"채택 답변: 2번 버스를 타고 시청역에서 내리세요.",
			"지역 문맥: 서울특별시 중구"
		);

		AcceptedAnswerKnowledgeDocument first = factory.create(snapshot).orElseThrow();
		AcceptedAnswerKnowledgeDocument second = factory.create(snapshot).orElseThrow();

		assertThat(first).isEqualTo(second);
		assertThat(first.chunkText()).isEqualTo(expectedChunk);
		assertThat(first.contentHash())
			.isEqualTo("cd80baa201393206e672002b9ee12e2a841b0b616c6fc940e825c6a33f96a912");
		assertThat(first.displayName()).isEqualTo("AI 교통 문의");
		assertThat(first.geoScope()).isEqualTo(GeoScope.local);
		assertThat(first.regionContext())
			.isEqualTo(RegionContext.korea("서울특별시", "중구", null, null));
		assertThat(first.anchorLatitude()).isEqualTo(37.5665d);
		assertThat(first.anchorLongitude()).isEqualTo(126.978d);
	}

	@Test
	void excludesPiiAndExactLocationWhilePreservingTheMeaningfulAnswerAndCoarseRegion() {
		AcceptedAnswerKnowledgeDocument document = factory.create(
			new AcceptedAnswerKnowledgeSnapshot(
				"우리집 010-1234-5678 버스 질문",
				"대한민국 서울특별시 중구 태평로1가 31, 101동 202호에서 "
					+ "user@example.com 또는 02-123-4567로 연락하세요. "
					+ "주민번호 900101-1234567, 카드 1234-5678-9012-3456, "
					+ "계좌 110-123-456789, 좌표 37.5665 / 126.9780",
				"우리집이나 세종대로 110에서 만나지 말고 "
					+ "2번 버스를 타고 시청역에서 내리세요.",
				seoulLocation,
				GeoScope.regional
			)
		).orElseThrow();

		assertThat(document.chunkText())
			.contains("채택 답변:", "2번 버스를 타고 시청역에서 내리세요", "지역 문맥: 서울특별시 중구")
			.doesNotContain(
				"우리집",
				"대한민국 서울특별시 중구 태평로1가 31",
				"태평로1가",
				"101동",
				"202호",
				"user@example.com",
				"010-1234-5678",
				"02-123-4567",
				"900101-1234567",
				"1234-5678-9012-3456",
				"110-123-456789",
				"37.5665",
				"126.9780",
				"세종대로 110"
			);
	}

	@Test
	void usesSafeFallbacksGeneralScopeAndNoRegionLineWithoutATrustedSido() {
		StoredLocationSnapshot unparsedLocation = new StoredLocationSnapshot(
			35.6895d,
			139.6917d,
			"Tokyo Shinjuku",
			"",
			""
		);

		AcceptedAnswerKnowledgeDocument document = factory.create(
			new AcceptedAnswerKnowledgeSnapshot(
				"010-1234-5678",
				" \n ",
				"지하철 2호선을 이용하세요.",
				unparsedLocation,
				null
			)
		).orElseThrow();

		assertThat(document.chunkText()).isEqualTo(String.join("\n",
			"질문 제목: 제목 없음",
			"질문 내용: 내용 없음",
			"채택 답변: 지하철 2호선을 이용하세요."
		));
		assertThat(document.displayName()).isEqualTo("채택된 답변");
		assertThat(document.geoScope()).isEqualTo(GeoScope.general);
		assertThat(document.regionContext()).isEqualTo(RegionContext.empty());
	}

	@Test
	void truncatesTheDisplayNameAtTwoHundredUnicodeCodePoints() {
		String title = "가".repeat(199) + "😀" + "나";

		AcceptedAnswerKnowledgeDocument document = factory.create(
			new AcceptedAnswerKnowledgeSnapshot(
				title,
				"본문",
				"의미 있는 채택 답변입니다.",
				seoulLocation,
				GeoScope.general
			)
		).orElseThrow();

		assertThat(document.displayName()).isEqualTo("가".repeat(199) + "😀");
		assertThat(document.displayName().codePointCount(0, document.displayName().length()))
			.isEqualTo(200);
		assertThat(document.chunkText()).contains("질문 제목: " + title);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "\n\t", "010-1234-5678 user@example.com"})
	void rejectsAcceptedAnswersWithNoMeaningfulSanitizedText(String acceptedAnswer) {
		AcceptedAnswerKnowledgeSnapshot snapshot = new AcceptedAnswerKnowledgeSnapshot(
			"질문 제목",
			"질문 내용",
			acceptedAnswer,
			seoulLocation,
			GeoScope.local
		);

		assertThat(factory.create(snapshot)).isEmpty();
	}

	@Test
	void carriesNoUserOrAuthorIdentityAndNoKnowledgeGraphRelations() {
		assertThat(recordComponentNames(AcceptedAnswerKnowledgeSnapshot.class))
			.containsExactly(
				"questionTitle",
				"questionBody",
				"acceptedAnswer",
				"location",
				"persistedGeoScope"
			);
		assertThat(recordComponentNames(AcceptedAnswerKnowledgeDocument.class))
			.containsExactly(
				"displayName",
				"contentHash",
				"chunkText",
				"geoScope",
				"regionContext",
				"anchorLatitude",
				"anchorLongitude"
			);
	}

	private static String[] recordComponentNames(Class<?> type) {
		return Arrays.stream(type.getRecordComponents())
			.map(component -> component.getName())
			.toArray(String[]::new);
	}
}
