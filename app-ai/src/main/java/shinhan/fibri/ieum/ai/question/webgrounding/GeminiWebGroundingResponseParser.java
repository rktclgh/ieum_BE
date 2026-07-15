package shinhan.fibri.ieum.ai.question.webgrounding;

import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingChunk;
import com.google.genai.types.GroundingChunkWeb;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.GroundingSupport;
import com.google.genai.types.Part;
import com.google.genai.types.Segment;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

final class GeminiWebGroundingResponseParser {

	private static final int MAX_CITATIONS = 8;
	private static final int MAX_TITLE_LENGTH = 500;
	private static final int MAX_MODEL_LENGTH = 120;
	private static final int MAX_REQUEST_ID_LENGTH = 200;

	private final PublicWebSourceUriValidator uriValidator;
	private final MaterialClaimCoverageValidator coverageValidator;

	GeminiWebGroundingResponseParser(
		PublicWebSourceUriValidator uriValidator,
		MaterialClaimCoverageValidator coverageValidator
	) {
		this.uriValidator = Objects.requireNonNull(uriValidator, "uriValidator must not be null");
		this.coverageValidator = Objects.requireNonNull(
			coverageValidator,
			"coverageValidator must not be null"
		);
	}

	Optional<WebGroundedAnswer> parse(
		GenerateContentResponse response,
		WebGroundingProperties properties,
		Instant generatedAt
	) {
		try {
			return parseSafely(response, properties, generatedAt);
		}
		catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private Optional<WebGroundedAnswer> parseSafely(
		GenerateContentResponse response,
		WebGroundingProperties properties,
		Instant generatedAt
	) {
		Objects.requireNonNull(response, "response must not be null");
		Objects.requireNonNull(properties, "properties must not be null");
		Objects.requireNonNull(generatedAt, "generatedAt must not be null");

		List<Candidate> candidates = response.candidates().orElse(null);
		if (candidates == null || candidates.size() != 1) {
			return Optional.empty();
		}
		Candidate candidate = candidates.get(0);
		if (!validCandidateIndex(candidate) || !validFinishReason(candidate)) {
			return Optional.empty();
		}

		String answer = answerText(candidate);
		if (answer == null) {
			return Optional.empty();
		}
		GroundingMetadata metadata = candidate.groundingMetadata().orElse(null);
		if (metadata == null) {
			return Optional.empty();
		}
		List<GroundingChunk> chunks = metadata.groundingChunks().orElse(null);
		List<GroundingSupport> supports = metadata.groundingSupports().orElse(null);
		if (chunks == null || chunks.isEmpty() || supports == null || supports.isEmpty()) {
			return Optional.empty();
		}

		Map<CitationRange, WebGroundedCitation> citationsByRange = new LinkedHashMap<>();
		for (GroundingSupport support : supports) {
			Optional<WebGroundedCitation> citation = parseSupport(answer, chunks, support);
			if (citation.isEmpty()) {
				return Optional.empty();
			}
			WebGroundedCitation value = citation.orElseThrow();
			CitationRange range = new CitationRange(value.startIndex(), value.endIndex());
			WebGroundedCitation existing = citationsByRange.get(range);
			if (existing == null || value.score().compareTo(existing.score()) > 0) {
				citationsByRange.put(range, value);
			}
			if (citationsByRange.size() > MAX_CITATIONS) {
				return Optional.empty();
			}
		}

		List<WebGroundedCitation> citations = List.copyOf(citationsByRange.values());
		if (citations.isEmpty()
			|| !coverageValidator.coversEveryMaterialSentence(answer, citations)) {
			return Optional.empty();
		}

		BigDecimal groundingScore = citations.stream()
			.map(WebGroundedCitation::score)
			.min(BigDecimal::compareTo)
			.orElseThrow();
		GenerateContentResponseUsageMetadata usage = response.usageMetadata().orElse(null);
		return Optional.of(new WebGroundedAnswer(
			answer,
			citations,
			"gemini",
			model(response.modelVersion(), properties.model()),
			properties.promptVersion(),
			generatedAt,
			usage == null ? null : nonNegative(usage.promptTokenCount()),
			usage == null ? null : nonNegative(usage.candidatesTokenCount()),
			boundedOptional(response.responseId(), MAX_REQUEST_ID_LENGTH),
			groundingScore
		));
	}

	private boolean validCandidateIndex(Candidate candidate) {
		return candidate.index().map(index -> index == 0).orElse(true);
	}

	private boolean validFinishReason(Candidate candidate) {
		return candidate.finishReason()
			.map(reason -> reason.knownEnum() == FinishReason.Known.STOP)
			.orElse(true);
	}

	private String answerText(Candidate candidate) {
		Content content = candidate.content().orElse(null);
		if (content == null) {
			return null;
		}
		List<Part> parts = content.parts().orElse(null);
		if (parts == null || parts.size() != 1) {
			return null;
		}
		Part part = parts.get(0);
		if (part.thought().orElse(false)) {
			return null;
		}
		String text = part.text().orElse(null);
		return text == null || text.isBlank() ? null : text;
	}

	private Optional<WebGroundedCitation> parseSupport(
		String answer,
		List<GroundingChunk> chunks,
		GroundingSupport support
	) {
		Segment segment = support.segment().orElse(null);
		if (segment == null
			|| segment.partIndex().orElse(0) != 0
			|| segment.endIndex().isEmpty()
			|| segment.text().isEmpty()) {
			return Optional.empty();
		}
		Optional<Utf8CitationRangeResolver.Range> resolved = Utf8CitationRangeResolver.resolve(
			answer,
			segment.startIndex().orElse(0),
			segment.endIndex().orElseThrow(),
			segment.text().orElseThrow()
		);
		if (resolved.isEmpty()) {
			return Optional.empty();
		}

		List<Integer> chunkIndices = support.groundingChunkIndices().orElse(null);
		if (chunkIndices == null || chunkIndices.isEmpty()) {
			return Optional.empty();
		}
		for (Integer chunkIndex : chunkIndices) {
			if (chunkIndex == null || chunkIndex < 0 || chunkIndex >= chunks.size()) {
				return Optional.empty();
			}
		}

		Optional<List<Float>> confidenceScores = support.confidenceScores();
		if (confidenceScores.isEmpty()) {
			for (Integer chunkIndex : chunkIndices) {
				Optional<WebGroundedCitation> citation = citation(
					chunks.get(chunkIndex),
					resolved.orElseThrow(),
					BigDecimal.ZERO
				);
				if (citation.isPresent()) {
					return citation;
				}
			}
			return Optional.empty();
		}

		List<Float> scores = confidenceScores.orElseThrow();
		if (scores.isEmpty() || scores.size() != chunkIndices.size()) {
			return Optional.empty();
		}
		for (Float score : scores) {
			if (score == null || !Float.isFinite(score) || score < 0.0f || score > 1.0f) {
				return Optional.empty();
			}
		}

		WebGroundedCitation selected = null;
		for (int index = 0; index < chunkIndices.size(); index++) {
			BigDecimal score = new BigDecimal(Float.toString(scores.get(index)));
			Optional<WebGroundedCitation> candidate = citation(
				chunks.get(chunkIndices.get(index)),
				resolved.orElseThrow(),
				score
			);
			if (candidate.isPresent()
				&& (selected == null
					|| candidate.orElseThrow().score().compareTo(selected.score()) > 0)) {
				selected = candidate.orElseThrow();
			}
		}
		return Optional.ofNullable(selected);
	}

	private Optional<WebGroundedCitation> citation(
		GroundingChunk chunk,
		Utf8CitationRangeResolver.Range range,
		BigDecimal score
	) {
		GroundingChunkWeb web = chunk.web().orElse(null);
		if (web == null) {
			return Optional.empty();
		}
		String title = web.title().orElse(null);
		if (title == null) {
			return Optional.empty();
		}
		title = title.trim();
		if (title.isBlank() || title.length() > MAX_TITLE_LENGTH) {
			return Optional.empty();
		}
		Optional<URI> uri = uriValidator.validate(web.uri().orElse(null));
		if (uri.isEmpty()) {
			return Optional.empty();
		}
		return Optional.of(new WebGroundedCitation(
			title,
			uri.orElseThrow(),
			range.excerpt(),
			score,
			range.startIndex(),
			range.endIndex()
		));
	}

	private String model(Optional<String> rawModel, String fallback) {
		String normalized = boundedOptional(rawModel, MAX_MODEL_LENGTH);
		return normalized == null ? fallback : normalized;
	}

	private String boundedOptional(Optional<String> rawValue, int maxLength) {
		if (rawValue.isEmpty()) {
			return null;
		}
		String normalized = rawValue.orElseThrow().trim();
		return normalized.isBlank() || normalized.length() > maxLength ? null : normalized;
	}

	private Integer nonNegative(Optional<Integer> rawValue) {
		return rawValue.filter(value -> value >= 0).orElse(null);
	}

	private record CitationRange(int startIndex, int endIndex) {
	}
}
