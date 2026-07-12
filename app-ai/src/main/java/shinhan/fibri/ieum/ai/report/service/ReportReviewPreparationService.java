package shinhan.fibri.ieum.ai.report.service;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import shinhan.fibri.ieum.ai.report.domain.ReportReviewEvidenceMessage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewMessage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;

@Service
@ConditionalOnProperty(prefix = "app.ai.features", name = "report-review-enabled", havingValue = "true")
public class ReportReviewPreparationService {

	private final ReportReviewRequestValidator requestValidator;
	private final ReportEvidenceImageBatchCollector imageBatchCollector;

	public ReportReviewPreparationService(
		ReportReviewRequestValidator requestValidator,
		ReportEvidenceImageBatchCollector imageBatchCollector
	) {
		this.requestValidator = requestValidator;
		this.imageBatchCollector = imageBatchCollector;
	}

	public PreparedReportReview prepare(long pathReportId, ReportReviewRequest request) {
		requestValidator.validate(pathReportId, request);
		ReportEvidenceImageBatch imageBatch = imageBatchCollector.collect(request.messages());
		List<ReportReviewEvidenceMessage> evidenceMessages = request.messages().stream()
			.map(message -> evidenceMessage(message, imageBatch))
			.toList();
		return new PreparedReportReview(
			request.reportId(),
			request.reviewAttemptId(),
			request.reportedMessageId(),
			request.reason(),
			request.detail(),
			request.contextHash(),
			evidenceMessages,
			imageBatch
		);
	}

	private ReportReviewEvidenceMessage evidenceMessage(ReportReviewMessage message, ReportEvidenceImageBatch imageBatch) {
		return new ReportReviewEvidenceMessage(
			message.messageId(),
			message.actor(),
			message.content(),
			imageBatch.imagesByMessageId().containsKey(message.messageId())
		);
	}
}
