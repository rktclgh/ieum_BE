package shinhan.fibri.ieum.main.admin.report.service;

import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.main.report.domain.ReportTargetType;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class AdminReportJsonSanitizer {

	private static final String[] MESSAGE_ROOT_FIELDS = {"schemaVersion", "roomId"};
	private static final String[] MESSAGE_FIELDS = {
		"messageId", "senderId", "content", "imageFileId", "createdAt"
	};
	private static final String[] ANSWER_ROOT_FIELDS = {"schemaVersion", "targetType", "questionId"};
	private static final String[] ANSWER_FIELDS = {
		"answerId", "authorId", "isAi", "content", "imageFileIds", "createdAt"
	};
	private static final String[] AI_RESULT_FIELDS = {
		"category", "severity", "evidence", "matchedRules", "policySnapshot",
		"modelVersion", "promptVersion", "fallbackUsed"
	};

	private final ObjectMapper objectMapper;

	public AdminReportJsonSanitizer(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public JsonNode sanitizeContextSnapshot(ReportTargetType targetType, String rawJson) {
		ObjectNode source = parseObject(rawJson);
		if (source == null || targetType == null) {
			return null;
		}
		return targetType == ReportTargetType.message
			? sanitizeMessageSnapshot(source)
			: sanitizeAnswerSnapshot(source);
	}

	public JsonNode sanitizeAiResult(String rawJson) {
		ObjectNode source = parseObject(rawJson);
		if (source == null) {
			return null;
		}
		ObjectNode safe = objectMapper.createObjectNode();
		copyFields(source, safe, AI_RESULT_FIELDS);
		return safe;
	}

	private ObjectNode sanitizeMessageSnapshot(ObjectNode source) {
		ObjectNode safe = objectMapper.createObjectNode();
		copyFields(source, safe, MESSAGE_ROOT_FIELDS);
		copyMessageArray(source, safe, "before");
		copyObject(source, safe, "reported", MESSAGE_FIELDS);
		copyMessageArray(source, safe, "after");
		return safe;
	}

	private ObjectNode sanitizeAnswerSnapshot(ObjectNode source) {
		ObjectNode safe = objectMapper.createObjectNode();
		copyFields(source, safe, ANSWER_ROOT_FIELDS);
		copyObject(source, safe, "reported", ANSWER_FIELDS);
		return safe;
	}

	private void copyMessageArray(ObjectNode source, ObjectNode target, String fieldName) {
		JsonNode value = source.get(fieldName);
		if (value == null || !value.isArray()) {
			return;
		}
		ArrayNode safeArray = objectMapper.createArrayNode();
		for (JsonNode item : value) {
			if (item instanceof ObjectNode objectItem) {
				ObjectNode safeItem = objectMapper.createObjectNode();
				copyFields(objectItem, safeItem, MESSAGE_FIELDS);
				safeArray.add(safeItem);
			}
		}
		target.set(fieldName, safeArray);
	}

	private void copyObject(ObjectNode source, ObjectNode target, String fieldName, String... allowedFields) {
		JsonNode value = source.get(fieldName);
		if (!(value instanceof ObjectNode objectValue)) {
			return;
		}
		ObjectNode safeValue = objectMapper.createObjectNode();
		copyFields(objectValue, safeValue, allowedFields);
		target.set(fieldName, safeValue);
	}

	private void copyFields(ObjectNode source, ObjectNode target, String... allowedFields) {
		for (String field : allowedFields) {
			JsonNode value = source.get(field);
			if (value != null) {
				target.set(field, value.deepCopy());
			}
		}
	}

	private ObjectNode parseObject(String rawJson) {
		if (rawJson == null || rawJson.isBlank()) {
			return null;
		}
		try {
			JsonNode parsed = objectMapper.readTree(rawJson);
			return parsed instanceof ObjectNode object ? object : null;
		} catch (JacksonException exception) {
			return null;
		}
	}
}
