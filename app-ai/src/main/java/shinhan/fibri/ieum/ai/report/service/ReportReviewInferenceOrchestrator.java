package shinhan.fibri.ieum.ai.report.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;
import shinhan.fibri.ieum.ai.report.domain.ReportReviewEvidenceMessage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewResponse;

public class ReportReviewInferenceOrchestrator {

	private final PolicySnapshotProvider policySnapshotProvider;
	private final ReportReviewModelGateway modelGateway;
	private final ReportPolicyEvaluator policyEvaluator;
	private final ObjectMapper objectMapper;

	public ReportReviewInferenceOrchestrator(
		PolicySnapshotProvider policySnapshotProvider,
		ReportReviewModelGateway modelGateway,
		ReportPolicyEvaluator policyEvaluator,
		ObjectMapper objectMapper
	) {
		this.policySnapshotProvider = Objects.requireNonNull(policySnapshotProvider, "policySnapshotProvider must not be null");
		this.modelGateway = Objects.requireNonNull(modelGateway, "modelGateway must not be null");
		this.policyEvaluator = Objects.requireNonNull(policyEvaluator, "policyEvaluator must not be null");
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper must not be null");
	}

	public ReportReviewResponse review(PreparedReportReview preparedReview) {
		ReportPolicySnapshot snapshot = policySnapshotProvider.loadActiveSnapshot();
		ReportReviewInference inference = modelGateway.review(
			preparedReview,
			snapshot,
			modelOutput -> policyEvaluator.evaluate(snapshot, modelOutput, preparedReview.evidenceMessages())
		);
		ReportPolicyEvaluationResult evaluation = inference.evaluation();
		return new ReportReviewResponse(
			evaluation.decision().name(),
			evaluation.category(),
			evaluation.severity() == null ? null : evaluation.severity().name(),
			evaluation.confidence(),
			evaluation.reason(),
			evidenceJson(evaluation, preparedReview.evidenceMessages()),
			objectMapper.valueToTree(evaluation.matchedRules()),
			snapshot.policySetHash(),
			objectMapper.valueToTree(snapshot),
			inference.modelVersion(),
			inference.promptVersion(),
			inference.fallbackUsed(),
			objectMapper.valueToTree(inference.providerAttempts())
		);
	}

	private JsonNode evidenceJson(
		ReportPolicyEvaluationResult evaluation,
		List<ReportReviewEvidenceMessage> evidenceMessages
	) {
		Map<Long, ReportReviewEvidenceMessage> messagesById = evidenceMessages.stream()
			.collect(Collectors.toUnmodifiableMap(ReportReviewEvidenceMessage::messageId, Function.identity()));
		ArrayNode evidence = objectMapper.createArrayNode();
		for (Long messageId : evaluation.evidenceMessageIds()) {
			ReportReviewEvidenceMessage message = messagesById.get(messageId);
			if (message == null) {
				throw new IllegalStateException("evaluated evidence must exist in the prepared review");
			}
			ObjectNode item = evidence.addObject();
			item.put("messageId", messageId);
			item.put("type", evidenceType(message));
		}
		return evidence;
	}

	private String evidenceType(ReportReviewEvidenceMessage message) {
		if (message.hasText() && message.verifiedImage()) {
			return "both";
		}
		return message.verifiedImage() ? "image" : "text";
	}
}
