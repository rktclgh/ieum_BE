package shinhan.fibri.ieum.main.admin.audit.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.main.admin.audit.domain.AdminAuditAction;

@Repository
public class AdminAuditLogWriter {

	private static final Map<AdminAuditAction, AuditContract> CONTRACTS = Map.of(
		AdminAuditAction.USER_SANCTION_CREATED,
		new AuditContract("user", Set.of("sanctionId", "type", "reason", "endsAt")),
		AdminAuditAction.USER_ACTIVATED,
		new AuditContract("user", Set.of("releasedSanctionIds", "previousStatus", "newStatus")),
		AdminAuditAction.USER_ROLE_CHANGED,
		new AuditContract("user", Set.of("previousRole", "newRole")),
		AdminAuditAction.REPORT_CONFIRMED,
		new AuditContract("report", Set.of("previousDecision", "newDecision")),
		AdminAuditAction.REPORT_DISMISSED,
		new AuditContract("report", Set.of("previousDecision", "newDecision")),
		AdminAuditAction.INQUIRY_ANSWERED,
		new AuditContract("inquiry", Set.of("answerLength")),
		AdminAuditAction.KNOWLEDGE_RELATION_APPROVED,
		new AuditContract("knowledge_relation_candidate", Set.of(
			"sourceId",
			"relationId",
			"previousStatus",
			"newStatus",
			"version"
		)),
		AdminAuditAction.KNOWLEDGE_RELATION_REJECTED,
		new AuditContract("knowledge_relation_candidate", Set.of(
			"sourceId",
			"previousStatus",
			"newStatus",
			"version",
			"reason"
		)),
		AdminAuditAction.QUESTION_HARD_DELETED,
		new AuditContract("question", Set.of("deletedFileCount", "wasSoftDeleted")),
		AdminAuditAction.MEETING_HARD_DELETED,
		new AuditContract("meeting", Set.of("deletedFileCount", "wasSoftDeleted"))
	);

	private final JdbcClient jdbc;
	private final ObjectMapper objectMapper;

	public AdminAuditLogWriter(JdbcClient jdbc, ObjectMapper objectMapper) {
		this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	public void append(
		Long actorUserId,
		AdminAuditAction action,
		String targetType,
		long targetId,
		Map<String, ?> details
	) {
		Long requiredActorUserId = Objects.requireNonNull(actorUserId, "actorUserId must not be null");
		AdminAuditAction requiredAction = Objects.requireNonNull(action, "action must not be null");
		AuditContract contract = CONTRACTS.get(requiredAction);
		if (!contract.targetType().equals(targetType)) {
			throw new IllegalArgumentException("Unexpected administrator audit target type: " + targetType);
		}
		Map<String, ?> requiredDetails = Objects.requireNonNull(details, "details must not be null");
		if (!contract.detailKeys().equals(requiredDetails.keySet())) {
			throw new IllegalArgumentException(
				"Unexpected administrator audit detail keys for " + requiredAction + ": " + requiredDetails.keySet()
			);
		}
		if (!requiredDetails.values().stream().allMatch(AdminAuditLogWriter::isAllowedValue)) {
			throw new IllegalArgumentException("Administrator audit details contain a non-primitive value");
		}

		String serializedDetails = serialize(requiredDetails);
		jdbc.sql("""
			INSERT INTO admin_audit_logs(actor_user_id, action, target_type, target_id, details)
			VALUES (:actorUserId, :action, :targetType, :targetId, CAST(:details AS jsonb))
			""")
			.param("actorUserId", requiredActorUserId)
			.param("action", requiredAction.name())
			.param("targetType", targetType)
			.param("targetId", targetId)
			.param("details", serializedDetails)
			.update();
	}

	private String serialize(Map<String, ?> details) {
		try {
			String serialized = objectMapper.writeValueAsString(details);
			JsonNode json = objectMapper.readTree(serialized);
			if (!json.isObject()) {
				throw new IllegalArgumentException("Administrator audit details must serialize to a JSON object");
			}
			return serialized;
		} catch (JsonProcessingException | RuntimeException exception) {
			if (exception instanceof IllegalArgumentException illegalArgumentException
				&& "Administrator audit details must serialize to a JSON object".equals(exception.getMessage())) {
				throw illegalArgumentException;
			}
			throw new IllegalStateException("Failed to serialize administrator audit details", exception);
		}
	}

	private static boolean isAllowedValue(Object value) {
		if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
			return true;
		}
		if (value instanceof List<?> values) {
			return values.stream().allMatch(AdminAuditLogWriter::isScalar);
		}
		return false;
	}

	private static boolean isScalar(Object value) {
		return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
	}

	private record AuditContract(String targetType, Set<String> detailKeys) {
	}
}
