package shinhan.fibri.ieum.ai.knowledge.accepted;

import java.util.Objects;
import java.util.regex.Pattern;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;

public record AcceptedAnswerKnowledgeDocument(
	String displayName,
	String contentHash,
	String chunkText,
	GeoScope geoScope,
	RegionContext regionContext,
	double anchorLatitude,
	double anchorLongitude
) {

	private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

	public AcceptedAnswerKnowledgeDocument {
		displayName = required(displayName, "displayName");
		contentHash = required(contentHash, "contentHash");
		chunkText = required(chunkText, "chunkText");
		Objects.requireNonNull(geoScope, "geoScope must not be null");
		Objects.requireNonNull(regionContext, "regionContext must not be null");
		if (displayName.codePointCount(0, displayName.length()) > 200) {
			throw new IllegalArgumentException("displayName must not exceed 200 Unicode code points");
		}
		if (!SHA_256.matcher(contentHash).matches()) {
			throw new IllegalArgumentException("contentHash must be a lowercase SHA-256 hash");
		}
	}

	private static String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}
}
