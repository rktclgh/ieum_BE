package shinhan.fibri.ieum.ai.question.grounding;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record GroundingValidation(
	boolean supported,
	BigDecimal score,
	List<String> unsupportedClaims
) {

	private static final int MAX_UNSUPPORTED_CLAIMS = 8;
	private static final int MAX_UNSUPPORTED_CLAIM_LENGTH = 500;

	public GroundingValidation {
		score = Objects.requireNonNull(score, "score must not be null");
		if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException("score must be between 0 to 1");
		}
		Objects.requireNonNull(unsupportedClaims, "unsupportedClaims must not be null");
		List<String> normalizedClaims = normalizeClaims(unsupportedClaims);
		if (supported && !normalizedClaims.isEmpty()) {
			throw new IllegalArgumentException("unsupportedClaims must be empty when supported is true");
		}
		if (!supported && (normalizedClaims.isEmpty() || normalizedClaims.size() > MAX_UNSUPPORTED_CLAIMS)) {
			throw new IllegalArgumentException("unsupportedClaims must contain 1 to 8 items when supported is false");
		}
		unsupportedClaims = List.copyOf(normalizedClaims);
	}

	private static List<String> normalizeClaims(List<String> claims) {
		List<String> normalized = new ArrayList<>(claims.size());
		for (String claim : claims) {
			if (claim == null || claim.isBlank()) {
				throw new IllegalArgumentException("unsupported claim must not be blank");
			}
			String trimmed = claim.trim();
			if (trimmed.length() > MAX_UNSUPPORTED_CLAIM_LENGTH) {
				throw new IllegalArgumentException("unsupported claim is too long");
			}
			normalized.add(trimmed);
		}
		return normalized;
	}
}
