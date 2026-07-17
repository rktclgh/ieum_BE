package shinhan.fibri.ieum.ai.knowledge.relations;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.ai.bedrock.converse.BedrockChatOptions;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeDocument;

public final class BedrockKnowledgeRelationCandidateExtractor implements KnowledgeRelationCandidateExtractor {

	private static final String PROVIDER = "bedrock";
	private static final double TEMPERATURE = 0.0d;

	private final ChatModel chatModel;
	private final ObjectMapper objectMapper;
	private final String model;
	private final int maxTokens;

	public BedrockKnowledgeRelationCandidateExtractor(
		ChatModel chatModel,
		ObjectMapper objectMapper,
		String model,
		int maxTokens
	) {
		this.chatModel = Objects.requireNonNull(chatModel, "chatModel must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
		this.model = required(model, "model");
		this.maxTokens = maxTokens;
	}

	@Override
	public CandidateExtractionResult extract(AcceptedAnswerKnowledgeDocument document) {
		Objects.requireNonNull(document, "document must not be null");
		try {
			ChatResponse response = chatModel.call(prompt(document));
			return parse(response);
		}
		catch (InvalidKnowledgeRelationExtractionOutputException exception) {
			throw exception;
		}
		catch (KnowledgeRelationExtractionProviderException exception) {
			throw exception;
		}
		catch (RuntimeException exception) {
			throw new KnowledgeRelationExtractionProviderException("provider failure", exception);
		}
	}

	private Prompt prompt(AcceptedAnswerKnowledgeDocument document) {
		BedrockChatOptions options = BedrockChatOptions.builder()
			.model(model)
			.temperature(TEMPERATURE)
			.maxTokens(maxTokens)
			.build();
		return new Prompt(
			List.of(
				new SystemMessage("""
					Extract up to five Korean public-service knowledge graph relation candidates.
					Return only JSON: {"candidates":[{"subject":"","predicate":"","object":"","confidence":0.0,"evidence":""}]}.
					Predicate must be one of requires, applies_to, located_in, exception_of, prevents, supports,
					has_deadline, depends_on, reported_to, used_for. Evidence must be an exact substring.
					"""),
				new UserMessage(document.chunkText())
			),
			options
		);
	}

	private CandidateExtractionResult parse(ChatResponse response) {
		if (response == null) {
			throw new InvalidKnowledgeRelationExtractionOutputException("empty response");
		}
		Generation generation = response.getResult();
		if (generation == null || generation.getOutput() == null || generation.getOutput().getText() == null) {
			throw new InvalidKnowledgeRelationExtractionOutputException("empty response");
		}
		try {
			JsonNode root = objectMapper.readTree(generation.getOutput().getText());
			JsonNode candidateNodes = root.get("candidates");
			if (candidateNodes == null || !candidateNodes.isArray()) {
				throw new IllegalArgumentException("missing candidates");
			}
			List<ExtractedKnowledgeRelationCandidate> candidates = new ArrayList<>();
			for (JsonNode node : candidateNodes) {
				candidates.add(new ExtractedKnowledgeRelationCandidate(
					text(node, "subject"),
					text(node, "predicate"),
					text(node, "object"),
					node.path("confidence").asDouble(Double.NaN),
					text(node, "evidence")
				));
			}
			return new CandidateExtractionResult(candidates, PROVIDER, model);
		}
		catch (RuntimeException | java.io.IOException exception) {
			throw new InvalidKnowledgeRelationExtractionOutputException("invalid provider response", exception);
		}
	}

	private String text(JsonNode node, String field) {
		JsonNode value = node.get(field);
		return value == null || !value.isTextual() ? null : value.textValue();
	}

	private String required(String value, String field) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(field + " must not be blank");
		}
		return value.trim();
	}
}
