package shinhan.fibri.ieum.ai.question.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class StoredAddressRegionParserTest {

	private final StoredAddressRegionParser parser = new StoredAddressRegionParser();

	@ParameterizedTest
	@MethodSource("koreanAddresses")
	void parsesStoredKoreanAddressIntoCoarseAdministrativeRegion(
		String address,
		RegionContext expected
	) {
		assertThat(parser.parse(address)).isEqualTo(expected);
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = {"   ", "Tokyo Shinjuku", "대한민국", "종로구 청운효자동"})
	void returnsEmptyWhenTheStoredAddressCannotBeParsed(String address) {
		assertThat(parser.parse(address)).isEqualTo(RegionContext.empty());
	}

	@Test
	void acceptsAnOptionalKoreaCountryPrefixWithoutExposingTheRawAddress() {
		RegionContext region = parser.parse("대한민국 서울특별시 강남구 역삼동 테헤란로 123");

		assertThat(region).isEqualTo(RegionContext.korea("서울특별시", "강남구", "역삼동", null));
		assertThat(region.toString()).doesNotContain("테헤란로", "123");
		assertThat(region.place()).isNull();
	}

	private static Stream<Arguments> koreanAddresses() {
		return Stream.of(
			Arguments.of(
				"서울특별시 종로구 청운효자동 자하문로 1",
				RegionContext.korea("서울특별시", "종로구", "청운효자동", null)
			),
			Arguments.of(
				"부산광역시 해운대구 우동 해운대로 100",
				RegionContext.korea("부산광역시", "해운대구", "우동", null)
			),
			Arguments.of(
				"경기도 수원시 영통구 매탄동 효원로 1",
				RegionContext.korea("경기도", "수원시 영통구", "매탄동", null)
			),
			Arguments.of(
				"세종특별자치시 조치원읍 새내로 1",
				RegionContext.korea("세종특별자치시", null, "조치원읍", null)
			),
			Arguments.of(
				"제주특별자치도 제주시 애월읍 애월로 1",
				RegionContext.korea("제주특별자치도", "제주시", "애월읍", null)
			),
			Arguments.of(
				"전북특별자치도 전주시 완산구 효자동 완산로 1",
				RegionContext.korea("전북특별자치도", "전주시 완산구", "효자동", null)
			),
			Arguments.of(
				"서울특별시 중구 태평로1가 세종대로 110",
				RegionContext.korea("서울특별시", "중구", "태평로1가", null)
			),
			Arguments.of(
				"서울특별시 중구 을지로1가 을지로 42",
				RegionContext.korea("서울특별시", "중구", "을지로1가", null)
			),
			Arguments.of(
				"전북특별자치도 전주시 완산구 효자동3가 홍산로 1",
				RegionContext.korea("전북특별자치도", "전주시 완산구", "효자동3가", null)
			)
		);
	}
}
