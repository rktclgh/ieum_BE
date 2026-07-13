package shinhan.fibri.ieum.ai.question.grounding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import shinhan.fibri.ieum.ai.question.citation.AnswerCitation;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerEvidence;

final class GroundingPromptPayloadFactory {

	private final ObjectMapper objectMapper;

	GroundingPromptPayloadFactory(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	ObjectNode create(LocalGroundingRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		ObjectNode payload = objectMapper.createObjectNode();

		ObjectNode question = payload.putObject("question");
		question.put("title", request.prompt().title());
		question.put("content", request.prompt().content());

		if (request.prompt().coarseRegion().isEmpty()) {
			payload.putNull("coarseRegion");
		}
		else {
			ObjectNode region = payload.putObject("coarseRegion");
			putNullable(region, "country", request.prompt().coarseRegion().country());
			putNullable(region, "sido", request.prompt().coarseRegion().sido());
			putNullable(region, "sigungu", request.prompt().coarseRegion().sigungu());
			putNullable(region, "eupMyeonDong", request.prompt().coarseRegion().eupMyeonDong());
		}

		ArrayNode evidence = payload.putArray("evidence");
		for (LocalAnswerEvidence item : request.prompt().evidence()) {
			ObjectNode node = evidence.addObject();
			node.put("evidenceIndex", item.evidenceIndex());
			node.put("title", item.title());
			node.put("excerpt", item.excerpt());
			node.put("sourceType", item.sourceType());
		}

		ObjectNode candidate = payload.putObject("candidate");
		candidate.put("answer", request.candidate().answer());
		ArrayNode citations = candidate.putArray("citations");
		for (AnswerCitation citation : request.candidate().citations()) {
			ObjectNode node = citations.addObject();
			node.put("evidenceIndex", citation.evidenceIndex());
			node.put("startIndex", citation.startIndex());
			node.put("endIndex", citation.endIndex());
		}
		return payload;
	}

	String serialize(ObjectNode payload) {
		try {
			return objectMapper.writeValueAsString(payload);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Unable to serialize the sanitized grounding prompt");
		}
	}

	private void putNullable(ObjectNode object, String field, String value) {
		if (value == null) {
			object.putNull(field);
		}
		else {
			object.put(field, value);
		}
	}
}
