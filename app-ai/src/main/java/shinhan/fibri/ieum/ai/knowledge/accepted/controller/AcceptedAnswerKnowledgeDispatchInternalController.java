package shinhan.fibri.ieum.ai.knowledge.accepted.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.ai.config.AcceptedAnswerKnowledgeDispatchProperties;
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeTaskLane;
import shinhan.fibri.ieum.ai.knowledge.accepted.service.AcceptedAnswerKnowledgeTaskSubmission;

@RestController
@RequestMapping("/ai/v1/internal/accepted-answer-jobs")
public class AcceptedAnswerKnowledgeDispatchInternalController {

	private final AcceptedAnswerKnowledgeTaskLane lane;
	private final int retryAfterSeconds;

	@Autowired
	public AcceptedAnswerKnowledgeDispatchInternalController(
		AcceptedAnswerKnowledgeTaskLane lane,
		AcceptedAnswerKnowledgeDispatchProperties properties
	) {
		this(lane, properties.redispatchDelaySeconds());
	}

	AcceptedAnswerKnowledgeDispatchInternalController(
		AcceptedAnswerKnowledgeTaskLane lane,
		int retryAfterSeconds
	) {
		this.lane = lane;
		this.retryAfterSeconds = retryAfterSeconds;
	}

	@PostMapping("/{answerId}/dispatch")
	public ResponseEntity<AcceptedAnswerKnowledgeDispatchResponse> dispatch(
		@PathVariable long answerId
	) {
		if (answerId < 1) {
			return ResponseEntity.badRequest()
				.body(new AcceptedAnswerKnowledgeDispatchResponse("invalid_answer_id"));
		}
		AcceptedAnswerKnowledgeTaskSubmission submission = lane.submit(answerId);
		return switch (submission) {
			case ENQUEUED -> accepted("enqueued");
			case ALREADY_ACTIVE -> accepted("already_active");
			case SATURATED -> unavailable("accepted_answer_ingestion_saturated");
			case DISABLED -> unavailable("accepted_answer_ingestion_disabled");
		};
	}

	private ResponseEntity<AcceptedAnswerKnowledgeDispatchResponse> accepted(String status) {
		return ResponseEntity.accepted().body(new AcceptedAnswerKnowledgeDispatchResponse(status));
	}

	private ResponseEntity<AcceptedAnswerKnowledgeDispatchResponse> unavailable(String status) {
		return ResponseEntity.status(503)
			.header(HttpHeaders.RETRY_AFTER, Integer.toString(retryAfterSeconds))
			.body(new AcceptedAnswerKnowledgeDispatchResponse(status));
	}
}
