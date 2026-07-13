package shinhan.fibri.ieum.ai.question.service;

import org.springframework.http.HttpStatus;

public enum QuestionAnswerJobDispatchResult {
	ENQUEUED(HttpStatus.ACCEPTED, "enqueued", false),
	ALREADY_ACTIVE(HttpStatus.ACCEPTED, "already_active", false),
	ALREADY_COMPLETED(HttpStatus.OK, "already_completed", false),
	INVARIANT_BREACH(HttpStatus.NOT_FOUND, "question_task_invariant_breach", false),
	CANCELLED_OR_DELETED(HttpStatus.GONE, "question_cancelled_or_deleted", false),
	DEAD(HttpStatus.CONFLICT, "question_answer_job_dead", false),
	SATURATED(HttpStatus.SERVICE_UNAVAILABLE, "question_answer_dispatch_saturated", true),
	DISABLED(HttpStatus.SERVICE_UNAVAILABLE, "question_answer_dispatch_disabled", true);

	private final HttpStatus httpStatus;
	private final String responseStatus;
	private final boolean retryAfterRequired;

	QuestionAnswerJobDispatchResult(HttpStatus httpStatus, String responseStatus, boolean retryAfterRequired) {
		this.httpStatus = httpStatus;
		this.responseStatus = responseStatus;
		this.retryAfterRequired = retryAfterRequired;
	}

	public HttpStatus httpStatus() {
		return httpStatus;
	}

	public String responseStatus() {
		return responseStatus;
	}

	public boolean retryAfterRequired() {
		return retryAfterRequired;
	}
}
