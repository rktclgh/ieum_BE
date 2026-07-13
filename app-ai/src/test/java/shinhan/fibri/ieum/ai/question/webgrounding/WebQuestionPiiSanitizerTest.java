package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.question.analysis.StoredLocationSnapshot;

class WebQuestionPiiSanitizerTest {

	private final WebQuestionPiiSanitizer sanitizer = new WebQuestionPiiSanitizer();
	private final StoredLocationSnapshot location = new StoredLocationSnapshot(
		37.5665d,
		126.978d,
		"대한민국 서울특별시 중구 태평로1가 31",
		"101동 202호",
		"우리집"
	);

	@Test
	void redactsStoredLocationAndCommonKoreanPersonalIdentifiersDeterministically() {
		String raw = """
			우리집 대한민국 서울특별시 중구 태평로1가 31 101동 202호에서 만나요.
			메일 user@example.com, 휴대전화 010-1234-5678, 유선전화 02-123-4567,
			주민번호 900101-1234567, 카드 1234-5678-9012-3456,
			좌표 (37.5665, 126.9780)는 보내지 말고 버스 탑승 방법만 알려주세요.
			""";

		String first = sanitizer.sanitize(raw, location);
		String second = sanitizer.sanitize(raw, location);

		assertThat(first)
			.isEqualTo(second)
			.contains("[REDACTED]", "버스 탑승 방법만 알려주세요")
			.doesNotContain(
				"우리집",
				"대한민국 서울특별시 중구 태평로1가 31",
				"101동 202호",
				"user@example.com",
				"010-1234-5678",
				"02-123-4567",
				"900101-1234567",
				"1234-5678-9012-3456",
				"37.5665",
				"126.9780"
			);
	}

	@Test
	void reportsRedactionOnlyTextAsMeaningless() {
		String sanitized = sanitizer.sanitize(
			"user@example.com / 010 1234 5678 / 37.5665,126.9780",
			location
		);

		assertThat(sanitizer.hasMeaningfulText(sanitized)).isFalse();
		assertThat(sanitizer.hasMeaningfulText("[REDACTED] 버스 이용 방법")).isTrue();
	}

	@Test
	void redactsSeparatedCoordinatesAndCommonPhoneSeparatorVariants() {
		String raw = "위도 37.5665 경도 126.9780, 010.1234.5678 또는 (02) 123-4567로 연락";

		String sanitized = sanitizer.sanitize(raw, location);

		assertThat(sanitized)
			.contains("[REDACTED]")
			.doesNotContain("37.5665", "126.9780", "010.1234.5678", "(02) 123-4567");
	}

	@Test
	void redactsStoredAddressWhenWhitespaceAndPunctuationDiffer() {
		String raw = "대한민국-서울특별시/중구,태평로1가 31과 101동-202호 근처 버스 이용법";

		String sanitized = sanitizer.sanitize(raw, location);

		assertThat(sanitized)
			.contains("[REDACTED]", "버스 이용법")
			.doesNotContain("태평로1가", "101동", "202호");
	}

	@Test
	void preservesOrdinaryHighPrecisionDecimalsThatAreNotLocationValues() {
		String raw = "원주율 3.141592와 할인율 0.125%는 질문 내용입니다.";

		assertThat(sanitizer.sanitize(raw, location))
			.contains("3.141592", "0.125%");
	}

	@Test
	void redactsOtherRoadAddressAndBuildingUnitShapes() {
		String raw = "서울특별시 중구 세종대로 110 10층에서 버스를 타려면 어떻게 하나요?";

		assertThat(sanitizer.sanitize(raw, location))
			.contains("[REDACTED]", "버스를 타려면")
			.doesNotContain("세종대로 110", "10층");
	}

	@Test
	void preservesKoreanPhrasesThatOnlyResembleAddressTokens() {
		String raw = "버스로 10분, 운동 10분, 지하철 2호선, 버스 110번 이용법을 알려주세요.";

		assertThat(sanitizer.sanitize(raw, location))
			.contains("버스로 10분", "운동 10분", "지하철 2호선", "버스 110번");
	}

	@Test
	void redactsRoadAndLotAddressesEndingInBeonji() {
		String raw = "세종대로 110번지와 태평로1가 31번지 근처 버스 이용법";

		assertThat(sanitizer.sanitize(raw, location))
			.contains("[REDACTED]", "버스 이용법")
			.doesNotContain("세종대로 110번지", "태평로1가 31번지");
	}
}
