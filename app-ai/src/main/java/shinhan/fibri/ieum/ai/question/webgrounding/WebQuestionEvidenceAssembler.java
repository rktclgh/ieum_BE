package shinhan.fibri.ieum.ai.question.webgrounding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public class WebQuestionEvidenceAssembler {

	private static final Comparator<WebGroundedCitation> ANSWER_ORDER = Comparator
		.comparingInt(WebGroundedCitation::startIndex)
		.thenComparingInt(WebGroundedCitation::endIndex)
		.thenComparing(citation -> citation.url().toASCIIString());

	private final ObjectMapper objectMapper;
	private final Clock clock;

	public WebQuestionEvidenceAssembler(ObjectMapper objectMapper, Clock clock) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	public List<JsonNode> assemble(WebGroundedAnswer answer) {
		Objects.requireNonNull(answer, "answer must not be null");
		List<WebGroundedCitation> ordered = new ArrayList<>(answer.citations());
		ordered.sort(ANSWER_ORDER);
		Instant retrievedAt = clock.instant();
		List<JsonNode> evidence = new ArrayList<>(ordered.size());
		for (WebGroundedCitation citation : ordered) {
			evidence.add(toEvidence(citation, retrievedAt));
		}
		return List.copyOf(evidence);
	}

	private ObjectNode toEvidence(WebGroundedCitation citation, Instant retrievedAt) {
		URI canonicalUri = canonicalize(citation.url());
		String canonicalUrl = canonicalUri.toASCIIString();
		ObjectNode node = objectMapper.createObjectNode();
		node.put("type", "web");
		node.put("title", citation.title());
		node.put("excerpt", citation.excerpt());
		node.put("url", canonicalUrl);
		node.put("domain", canonicalUri.getHost().toLowerCase(Locale.ROOT));
		node.put("contentHash", sha256(lengthPrefixed(canonicalUrl, citation.title(), citation.excerpt())));
		putDecimal(node, "score", citation.score());
		node.put("startIndex", citation.startIndex());
		node.put("endIndex", citation.endIndex());
		node.put("retrievedAt", retrievedAt.toString());
		return node;
	}

	private URI canonicalize(URI source) {
		URI normalized = source.normalize();
		String scheme = normalized.getScheme().toLowerCase(Locale.ROOT);
		String host = normalized.getHost().toLowerCase(Locale.ROOT);
		int port = normalized.getPort();
		if (("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443)) {
			port = -1;
		}
		String path = normalized.getRawPath();
		if (path == null || path.isEmpty()) {
			path = "/";
		}
		String formattedHost = host.indexOf(':') >= 0 && !host.startsWith("[") ? '[' + host + ']' : host;
		StringBuilder canonical = new StringBuilder(scheme)
			.append("://")
			.append(formattedHost);
		if (port >= 0) {
			canonical.append(':').append(port);
		}
		canonical.append(path);
		if (normalized.getRawQuery() != null) {
			canonical.append('?').append(normalized.getRawQuery());
		}
		return URI.create(canonical.toString());
	}

	private String sha256(String value) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 must be available", exception);
		}
	}

	private String lengthPrefixed(String... values) {
		StringBuilder framed = new StringBuilder();
		for (String value : values) {
			framed.append(value.length()).append(':').append(value);
		}
		return framed.toString();
	}

	private void putDecimal(ObjectNode node, String field, BigDecimal value) {
		node.put(field, value);
	}
}
