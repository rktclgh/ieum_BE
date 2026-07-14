package shinhan.fibri.ieum.ai.knowledge.accepted;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;
import shinhan.fibri.ieum.ai.question.analysis.StoredAddressRegionParser;
import shinhan.fibri.ieum.ai.question.analysis.StoredLocationSnapshot;
import shinhan.fibri.ieum.ai.question.webgrounding.WebQuestionPiiSanitizer;

public final class AcceptedAnswerKnowledgeDocumentFactory {

	private static final String TITLE_FALLBACK = "제목 없음";
	private static final String CONTENT_FALLBACK = "내용 없음";
	private static final String DISPLAY_NAME_FALLBACK = "채택된 답변";
	private static final int MAX_DISPLAY_NAME_CODE_POINTS = 200;
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private final WebQuestionPiiSanitizer sanitizer;
	private final StoredAddressRegionParser regionParser;

	public AcceptedAnswerKnowledgeDocumentFactory(
		WebQuestionPiiSanitizer sanitizer,
		StoredAddressRegionParser regionParser
	) {
		this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer must not be null");
		this.regionParser = Objects.requireNonNull(regionParser, "regionParser must not be null");
	}

	public Optional<AcceptedAnswerKnowledgeDocument> create(AcceptedAnswerKnowledgeSnapshot snapshot) {
		Objects.requireNonNull(snapshot, "snapshot must not be null");
		StoredLocationSnapshot location = snapshot.location();
		String acceptedAnswer = sanitize(snapshot.acceptedAnswer(), location);
		if (!sanitizer.hasMeaningfulText(acceptedAnswer)) {
			return Optional.empty();
		}

		String title = sanitize(snapshot.questionTitle(), location);
		String questionBody = sanitize(snapshot.questionBody(), location);
		RegionContext region = coarseRegion(location);
		String chunkText = canonicalChunk(title, questionBody, acceptedAnswer, region);
		String displayName = sanitizer.hasMeaningfulText(title) ? title : DISPLAY_NAME_FALLBACK;
		GeoScope geoScope = snapshot.persistedGeoScope() == null
			? GeoScope.general
			: snapshot.persistedGeoScope();

		return Optional.of(new AcceptedAnswerKnowledgeDocument(
			truncateCodePoints(displayName, MAX_DISPLAY_NAME_CODE_POINTS),
			sha256(chunkText),
			chunkText,
			geoScope,
			region,
			location.latitude(),
			location.longitude()
		));
	}

	private String sanitize(String value, StoredLocationSnapshot location) {
		return sanitizer.sanitize(value == null ? "" : value, location);
	}

	private RegionContext coarseRegion(StoredLocationSnapshot location) {
		String normalizedAddress = normalizeWhitespace(location.address());
		RegionContext parsed = regionParser.parse(normalizedAddress);
		if (parsed.isEmpty() || parsed.sido() == null) {
			return RegionContext.empty();
		}
		return RegionContext.korea(
			normalizeWhitespace(parsed.sido()),
			parsed.sigungu() == null ? null : normalizeWhitespace(parsed.sigungu()),
			null,
			null
		);
	}

	private String canonicalChunk(
		String title,
		String questionBody,
		String acceptedAnswer,
		RegionContext region
	) {
		List<String> lines = new ArrayList<>(4);
		lines.add("질문 제목: " + meaningfulOrFallback(title, TITLE_FALLBACK));
		lines.add("질문 내용: " + meaningfulOrFallback(questionBody, CONTENT_FALLBACK));
		lines.add("채택 답변: " + acceptedAnswer);
		if (!region.isEmpty()) {
			String regionText = region.sigungu() == null
				? region.sido()
				: region.sido() + ' ' + region.sigungu();
			lines.add("지역 문맥: " + regionText);
		}
		return String.join("\n", lines);
	}

	private String meaningfulOrFallback(String value, String fallback) {
		return sanitizer.hasMeaningfulText(value) ? value : fallback;
	}

	private static String truncateCodePoints(String value, int maximumCodePoints) {
		if (value.codePointCount(0, value.length()) <= maximumCodePoints) {
			return value;
		}
		return value.substring(0, value.offsetByCodePoints(0, maximumCodePoints));
	}

	private static String normalizeWhitespace(String value) {
		String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
		return WHITESPACE.matcher(normalized.strip()).replaceAll(" ");
	}

	private static String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available", exception);
		}
	}
}
