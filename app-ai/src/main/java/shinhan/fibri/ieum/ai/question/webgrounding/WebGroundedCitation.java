package shinhan.fibri.ieum.ai.question.webgrounding;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Locale;
import java.util.Objects;

public record WebGroundedCitation(
	String title,
	URI url,
	String excerpt,
	BigDecimal score,
	int startIndex,
	int endIndex
) {

	public WebGroundedCitation {
		title = requiredText(title, "title").trim();
		url = validateUrl(url);
		excerpt = requiredText(excerpt, "excerpt");
		score = probability(score, "score");
		if (startIndex < 0 || endIndex <= startIndex) {
			throw new IllegalArgumentException("citation range must be nonempty and non-negative");
		}
	}

	private static URI validateUrl(URI value) {
		URI url = Objects.requireNonNull(value, "url must not be null");
		String scheme = url.getScheme();
		if (scheme == null
			|| !("http".equals(scheme.toLowerCase(Locale.ROOT))
				|| "https".equals(scheme.toLowerCase(Locale.ROOT)))) {
			throw new IllegalArgumentException("url must use HTTP(S)");
		}
		if (url.getHost() == null || url.getHost().isBlank()) {
			throw new IllegalArgumentException("url must contain a host");
		}
		if (url.getUserInfo() != null) {
			throw new IllegalArgumentException("url must not contain userinfo");
		}
		return url;
	}

	private static String requiredText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value;
	}

	private static BigDecimal probability(BigDecimal value, String field) {
		if (value == null
			|| value.compareTo(BigDecimal.ZERO) < 0
			|| value.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException(field + " must be between 0 and 1");
		}
		return value;
	}
}
