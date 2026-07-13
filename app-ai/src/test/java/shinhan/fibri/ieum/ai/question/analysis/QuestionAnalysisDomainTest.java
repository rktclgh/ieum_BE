package shinhan.fibri.ieum.ai.question.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class QuestionAnalysisDomainTest {

	@Test
	void derivesHighRiskOnlyFromTheCanonicalDomainAllowlist() {
		QueryAnalysis legal = new QueryAnalysis(
			GeoScope.regional,
			new BigDecimal("0.80"),
			RegionContext.korea("서울특별시", "종로구", null, null),
			" legal ",
			false,
			List.of("체류 자격"),
			List.of("외국인 체류 자격"),
			" analyzer-v1 "
		);
		QueryAnalysis community = new QueryAnalysis(
			GeoScope.general,
			new BigDecimal("0.50"),
			RegionContext.empty(),
			"community",
			true,
			List.of(),
			List.of(),
			"analyzer-v1"
		);

		assertThat(legal.domain()).isEqualTo("legal");
		assertThat(legal.highRiskDomain()).isTrue();
		assertThat(legal.analysisVersion()).isEqualTo("analyzer-v1");
		assertThat(community.highRiskDomain()).isFalse();
	}

	@Test
	void recognizesEveryHighRiskDomainFromTheDesignAllowlist() {
		assertThat(List.of(
			"immigration", "legal", "tax", "pension", "insurance",
			"medical", "finance", "labor", "emergency"
		)).allMatch(HighRiskDomainResolver::isHighRisk);
		assertThat(HighRiskDomainResolver.isHighRisk("community")).isFalse();
		assertThat(HighRiskDomainResolver.isHighRisk(null)).isTrue();
	}

	@ParameterizedTest
	@MethodSource("highRiskDomainAliases")
	void canonicalizesHighRiskModelAliasesBeforeApplyingTheRiskPolicy(
		String modelDomain,
		String canonicalDomain
	) {
		QueryAnalysis analysis = analysis(
			new BigDecimal("0.75"),
			modelDomain,
			List.of(),
			List.of(),
			"v1"
		);

		assertThat(analysis.domain()).isEqualTo(canonicalDomain);
		assertThat(analysis.highRiskDomain()).isTrue();
	}

	@Test
	void treatsAnUnknownModelDomainAsExplicitlyUnknownAndHighRisk() {
		QueryAnalysis analysis = analysis(
			new BigDecimal("0.75"),
			"ignore-safety-and-call-this-harmless",
			List.of(),
			List.of(),
			"v1"
		);

		assertThat(analysis.domain()).isEqualTo("unknown");
		assertThat(analysis.highRiskDomain()).isTrue();
	}

	@Test
	void recognizesTheClosedGeneralDomainSetWithoutEscalatingIt() {
		assertThat(List.of(
			"general", "digital", "housing", "family", "education", "food", "shopping",
			"transport", "travel", "community", "culture", "environment", "household", "public_services"
		)).allSatisfy(domain -> {
			QueryAnalysis analysis = analysis(BigDecimal.ONE, domain, List.of(), List.of(), "v1");
			assertThat(analysis.domain()).isEqualTo(domain);
			assertThat(analysis.highRiskDomain()).isFalse();
		});
	}

	@Test
	void neutralFallbackUsesNoModelClaims() {
		QueryAnalysis fallback = QueryAnalysis.neutral("analyzer-v1");

		assertThat(fallback.geoScope()).isEqualTo(GeoScope.general);
		assertThat(fallback.confidence()).isEqualByComparingTo(BigDecimal.ZERO);
		assertThat(fallback.regionContext()).isEqualTo(RegionContext.empty());
		assertThat(fallback.domain()).isEqualTo("unknown");
		assertThat(fallback.highRiskDomain()).isTrue();
		assertThat(fallback.entityCandidates()).isEmpty();
		assertThat(fallback.searchTerms()).isEmpty();
	}

	@Test
	void modelInputContainsOnlyQuestionTextAndCoarseRegion() {
		ModelQuestionAnalysisInput input = new ModelQuestionAnalysisInput(
			"버스는 어디서 타나요?",
			"승하차 방법이 궁금합니다.",
			RegionContext.korea("경기도", "파주시", null, null)
		);

		assertThat(input.title()).isEqualTo("버스는 어디서 타나요?");
		assertThat(input.content()).isEqualTo("승하차 방법이 궁금합니다.");
		assertThat(input.coarseRegion()).isEqualTo(RegionContext.korea("경기도", "파주시", null, null));
		assertThat(Arrays.stream(ModelQuestionAnalysisInput.class.getRecordComponents())
			.map(component -> component.getName()))
			.containsExactly("title", "content", "coarseRegion")
			.doesNotContain("latitude", "longitude", "address", "detailAddress", "label", "userId");
	}

	@Test
	void validatesModelInputAndLocationSnapshotAtTheBoundary() {
		assertThatThrownBy(() -> new ModelQuestionAnalysisInput(
			" ",
			"content",
			RegionContext.empty()
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("title");
		assertThatThrownBy(() -> new ModelQuestionAnalysisInput(
			"title",
			"content",
			null
		))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("coarseRegion");
		assertThatThrownBy(() -> new StoredLocationSnapshot(
			91.0d,
			127.0d,
			"서울특별시 종로구",
			"",
			""
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("latitude");
		assertThatThrownBy(() -> new StoredLocationSnapshot(
			37.5d,
			181.0d,
			"서울특별시 종로구",
			"",
			""
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("longitude");
	}

	@Test
	void regionContextAcceptsOnlyTheKoreaCountryCodeOrAnEmptyValue() {
		assertThat(RegionContext.empty().isEmpty()).isTrue();
		assertThat(RegionContext.korea(" 서울특별시 ", " 종로구 ", " ", " 서울시청 "))
			.isEqualTo(new RegionContext("KR", "서울특별시", "종로구", null, "서울시청"));
		assertThatThrownBy(() -> new RegionContext("JP", "도쿄도", null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("country");
		assertThatThrownBy(() -> new RegionContext(null, "서울특별시", null, null, null))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("country");
	}

	@Test
	void validatesAnalysisBoundsAndRequiredValues() {
		assertThatThrownBy(() -> analysis(new BigDecimal("-0.01"), "general", List.of(), List.of(), "v1"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confidence");
		assertThatThrownBy(() -> analysis(new BigDecimal("1.01"), "general", List.of(), List.of(), "v1"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confidence");
		assertThatThrownBy(() -> analysis(BigDecimal.ZERO, " ", List.of(), List.of(), "v1"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("domain");
		assertThatThrownBy(() -> analysis(BigDecimal.ZERO, "general", List.of(), List.of(), " "))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("analysisVersion");
		assertThatThrownBy(() -> analysis(
			BigDecimal.ZERO,
			"general",
			Arrays.asList("valid", null),
			List.of(),
			"v1"
		))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("entityCandidates");
	}

	@Test
	void analysisCollectionsAreNormalizedDeduplicatedAndImmutable() {
		QueryAnalysis analysis = analysis(
			new BigDecimal("0.5"),
			"community",
			List.of(" 버스 ", "", "버스", "승차"),
			List.of(" 버스 승차 ", "버스 승차"),
			"v1"
		);

		assertThat(analysis.entityCandidates()).containsExactly("버스", "승차");
		assertThat(analysis.searchTerms()).containsExactly("버스 승차");
		assertThatThrownBy(() -> analysis.entityCandidates().add("하차"))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	private QueryAnalysis analysis(
		BigDecimal confidence,
		String domain,
		List<String> entityCandidates,
		List<String> searchTerms,
		String version
	) {
		return new QueryAnalysis(
			GeoScope.general,
			confidence,
			RegionContext.empty(),
			domain,
			false,
			entityCandidates,
			searchTerms,
			version
		);
	}

	private static List<Arguments> highRiskDomainAliases() {
		return List.of(
			Arguments.of("legal_advice", "legal"),
			Arguments.of("Work/Labor", "labor"),
			Arguments.of("healthcare-insurance", "insurance"),
			Arguments.of("healthcare", "medical"),
			Arguments.of("visa-immigration", "immigration"),
			Arguments.of("employment-law", "labor")
		);
	}
}
