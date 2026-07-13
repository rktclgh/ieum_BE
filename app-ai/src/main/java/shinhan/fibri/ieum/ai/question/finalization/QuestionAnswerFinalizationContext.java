package shinhan.fibri.ieum.ai.question.finalization;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import shinhan.fibri.ieum.ai.question.retrieval.GeoScope;

public record QuestionAnswerFinalizationContext(
	List<Float> embedding,
	String embeddingModel,
	GeoScope geoScope,
	BigDecimal geoScopeConfidence,
	JsonNode regionContext,
	String generationProvider,
	String generationModel,
	String retrievalConfigVersion,
	String fallbackReason,
	String promptVersion,
	BigDecimal groundingScore,
	List<JsonNode> evidence
) {

	public static final int EMBEDDING_DIMENSIONS = 768;
	public static final String EMBEDDING_MODEL = "gemini-embedding-2";

	public QuestionAnswerFinalizationContext {
		embedding = validateEmbedding(embedding);
		embeddingModel = requireExactEmbeddingModel(embeddingModel);
		geoScope = Objects.requireNonNull(geoScope, "geoScope must not be null");
		geoScopeConfidence = requireProbability(geoScopeConfidence, "geoScopeConfidence");
		regionContext = requireObject(regionContext, "regionContext");
		generationProvider = normalizeNullableText(generationProvider, "generationProvider");
		generationModel = normalizeNullableText(generationModel, "generationModel");
		retrievalConfigVersion = requireText(retrievalConfigVersion, "retrievalConfigVersion");
		fallbackReason = normalizeOptionalText(fallbackReason);
		promptVersion = normalizeNullableText(promptVersion, "promptVersion");
		groundingScore = requireProbability(groundingScore, "groundingScore");
		evidence = copyEvidence(evidence);
	}

	@Override
	public JsonNode regionContext() {
		return regionContext.deepCopy();
	}

	@Override
	public List<JsonNode> evidence() {
		return copyEvidence(evidence);
	}

	private static List<Float> validateEmbedding(List<Float> embedding) {
		Objects.requireNonNull(embedding, "embedding must not be null");
		if (embedding.size() != EMBEDDING_DIMENSIONS) {
			throw new IllegalArgumentException("embedding must contain exactly 768 values");
		}
		for (Float value : embedding) {
			if (value == null || !Float.isFinite(value)) {
				throw new IllegalArgumentException("embedding values must be finite");
			}
		}
		return List.copyOf(embedding);
	}

	private static String requireExactEmbeddingModel(String embeddingModel) {
		String model = requireText(embeddingModel, "embeddingModel");
		if (!EMBEDDING_MODEL.equals(model)) {
			throw new IllegalArgumentException("embeddingModel must be gemini-embedding-2");
		}
		return model;
	}

	private static BigDecimal requireProbability(BigDecimal value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(BigDecimal.ONE) > 0) {
			throw new IllegalArgumentException(field + " must be between 0 and 1");
		}
		return value;
	}

	private static JsonNode requireObject(JsonNode value, String field) {
		Objects.requireNonNull(value, field + " must not be null");
		if (!value.isObject()) {
			throw new IllegalArgumentException(field + " must be a JSON object");
		}
		return value.deepCopy();
	}

	private static String requireText(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}

	private static String normalizeOptionalText(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String normalizeNullableText(String value, String field) {
		if (value == null) {
			return null;
		}
		if (value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank when present");
		}
		return value.trim();
	}

	private static List<JsonNode> copyEvidence(List<JsonNode> evidence) {
		Objects.requireNonNull(evidence, "evidence must not be null");
		List<JsonNode> copies = new ArrayList<>(evidence.size());
		for (JsonNode item : evidence) {
			copies.add(QuestionAnswerEvidenceValidator.validateAndCopy(item));
		}
		return List.copyOf(copies);
	}
}
