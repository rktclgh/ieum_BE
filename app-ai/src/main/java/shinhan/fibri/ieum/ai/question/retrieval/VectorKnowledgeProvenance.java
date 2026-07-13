package shinhan.fibri.ieum.ai.question.retrieval;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

final class VectorKnowledgeProvenance {

	private static final Pattern CONTENT_HASH = Pattern.compile("^[0-9a-f]{64}$");
	private static final BigDecimal NEGATIVE_ONE = BigDecimal.valueOf(-1L);
	private static final BigDecimal ZERO = BigDecimal.ZERO;
	private static final BigDecimal ONE = BigDecimal.ONE;

	private VectorKnowledgeProvenance() {
	}

	static long positiveId(long value, String field) {
		if (value <= 0L) {
			throw new IllegalArgumentException(field + " must be positive");
		}
		return value;
	}

	static String requiredText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}

	static String optionalText(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	static String normalizedSourceGrade(String value) {
		String normalized = optionalText(value);
		return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
	}

	static String contentHash(String value) {
		String normalized = requiredText(value, "contentHash");
		if (!CONTENT_HASH.matcher(normalized).matches()) {
			throw new IllegalArgumentException("contentHash must contain exactly 64 lowercase hexadecimal characters");
		}
		return normalized;
	}

	static String canonicalUrl(String value) {
		String normalized = optionalText(value);
		if (normalized == null) {
			return null;
		}
		try {
			URI uri = new URI(normalized);
			String scheme = uri.getScheme();
			if (uri.isOpaque()
				|| scheme == null
				|| !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
				|| uri.getHost() == null
				|| uri.getHost().isBlank()
				|| uri.getUserInfo() != null) {
				throw invalidCanonicalUrl();
			}
		}
		catch (URISyntaxException exception) {
			throw invalidCanonicalUrl();
		}
		return normalized;
	}

	static double cosineSimilarity(double value) {
		if (!Double.isFinite(value) || value < -1.0d || value > 1.0d) {
			throw new IllegalArgumentException("cosineSimilarity must be finite and between -1 and 1");
		}
		return value;
	}

	static Double distanceKm(Double value) {
		if (value != null && (!Double.isFinite(value) || value < 0.0d)) {
			throw new IllegalArgumentException("distanceKm must be finite and non-negative");
		}
		return value;
	}

	static BigDecimal cosineScore(BigDecimal value) {
		return range(value, NEGATIVE_ONE, ONE, "cosineSimilarity");
	}

	static BigDecimal unitScore(BigDecimal value, String field) {
		return range(value, ZERO, ONE, field);
	}

	static BigDecimal distanceScore(BigDecimal value) {
		if (value != null && value.compareTo(ZERO) < 0) {
			throw new IllegalArgumentException("distanceKm must be non-negative");
		}
		return value;
	}

	private static BigDecimal range(BigDecimal value, BigDecimal lower, BigDecimal upper, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		if (value.compareTo(lower) < 0 || value.compareTo(upper) > 0) {
			throw new IllegalArgumentException(field + " must be between " + lower + " and " + upper);
		}
		return value;
	}

	private static IllegalArgumentException invalidCanonicalUrl() {
		return new IllegalArgumentException("canonicalUrl must be an HTTP(S) URL without userinfo");
	}
}
