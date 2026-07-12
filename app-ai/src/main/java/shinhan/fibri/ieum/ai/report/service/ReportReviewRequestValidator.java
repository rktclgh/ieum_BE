package shinhan.fibri.ieum.ai.report.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewMessage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;

@Service
public class ReportReviewRequestValidator {

	private static final int MAX_CONTEXT_MESSAGES = 41;
	private static final String REPORTED_USER = "reported_user";
	private static final Pattern ACTOR_ALIAS = Pattern.compile("reported_user|reporter|other_actor_[1-9][0-9]*");

	public void validate(long pathReportId, ReportReviewRequest request) {
		if (pathReportId < 1) {
			throw invalid("reportId must be positive");
		}
		if (request == null) {
			throw invalid("request must not be null");
		}
		if (request.reportId() != pathReportId) {
			throw invalid("reportId must match the request path");
		}
		if (request.reviewAttemptId() == null) {
			throw invalid("reviewAttemptId is required");
		}
		if (request.reportedMessageId() < 1) {
			throw invalid("reportedMessageId must be positive");
		}

		List<ReportReviewMessage> messages = request.messages();
		if (messages == null || messages.isEmpty() || messages.size() > MAX_CONTEXT_MESSAGES) {
			throw invalid("messages must contain between 1 and " + MAX_CONTEXT_MESSAGES + " items");
		}

		Set<Long> messageIds = new HashSet<>();
		for (ReportReviewMessage message : messages) {
			validateMessage(message);
			if (!messageIds.add(message.messageId())) {
				throw invalid("messageId must be unique within the context");
			}
		}

		ReportReviewMessage reportedMessage = messages.stream()
			.filter(message -> message != null && message.messageId() == request.reportedMessageId())
			.findFirst()
			.orElseThrow(() -> invalid("reportedMessageId must reference a context message"));
		if (!REPORTED_USER.equals(reportedMessage.actor())) {
			throw invalid("reportedMessageId must reference a reported_user message");
		}
	}

	private void validateMessage(ReportReviewMessage message) {
		if (message == null || message.messageId() < 1) {
			throw invalid("messageId must be positive");
		}
		if (message.actor() == null || !ACTOR_ALIAS.matcher(message.actor()).matches()) {
			throw invalid("actor must be a supported alias");
		}
		if ((message.content() == null || message.content().isBlank()) && message.image() == null) {
			throw invalid("message must contain content or image evidence");
		}
		if (message.image() != null && (isBlank(message.image().contentType()) || isBlank(message.image().presignedGetUrl()))) {
			throw invalid("image must include contentType and presignedGetUrl");
		}
	}

	private boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	private InvalidReportReviewRequestException invalid(String message) {
		return new InvalidReportReviewRequestException(message);
	}
}
