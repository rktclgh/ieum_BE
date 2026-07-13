package shinhan.fibri.ieum.ai.question.analysis;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

public record QueryAnalysis(
	GeoScope geoScope,
	BigDecimal confidence,
	RegionContext regionContext,
	String domain,
	boolean highRiskDomain,
	List<String> entityCandidates,
	List<String> searchTerms,
	String analysisVersion
) {

	private static final BigDecimal MIN_CONFIDENCE = BigDecimal.ZERO;
	private static final BigDecimal MAX_CONFIDENCE = BigDecimal.ONE;

	public QueryAnalysis {
		Objects.requireNonNull(geoScope, "geoScope must not be null");
		Objects.requireNonNull(confidence, "confidence must not be null");
		Objects.requireNonNull(regionContext, "regionContext must not be null");
		if (confidence.compareTo(MIN_CONFIDENCE) < 0 || confidence.compareTo(MAX_CONFIDENCE) > 0) {
			throw new IllegalArgumentException("confidence must be between 0 and 1");
		}
		if (domain == null || domain.isBlank()) {
			throw new IllegalArgumentException("domain must not be blank");
		}
		if (analysisVersion == null || analysisVersion.isBlank()) {
			throw new IllegalArgumentException("analysisVersion must not be blank");
		}
		QuestionDomain canonicalDomain = QuestionDomainResolver.resolve(domain);
		domain = canonicalDomain.name();
		highRiskDomain = canonicalDomain.highRisk();
		entityCandidates = normalizeTerms(entityCandidates, "entityCandidates");
		searchTerms = normalizeTerms(searchTerms, "searchTerms");
		analysisVersion = analysisVersion.trim();
	}

	public static QueryAnalysis neutral(String analysisVersion) {
		return new QueryAnalysis(
			GeoScope.general,
			BigDecimal.ZERO,
			RegionContext.empty(),
			"unknown",
			true,
			List.of(),
			List.of(),
			analysisVersion
		);
	}

	private static List<String> normalizeTerms(List<String> terms, String fieldName) {
		Objects.requireNonNull(terms, fieldName + " must not be null");
		LinkedHashSet<String> normalized = new LinkedHashSet<>();
		for (String term : terms) {
			if (term == null) {
				throw new IllegalArgumentException(fieldName + " must not contain null");
			}
			if (!term.isBlank()) {
				normalized.add(term.trim());
			}
		}
		return List.copyOf(normalized);
	}
}
