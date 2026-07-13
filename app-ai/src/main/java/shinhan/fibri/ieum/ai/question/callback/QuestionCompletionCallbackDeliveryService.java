package shinhan.fibri.ieum.ai.question.callback;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QuestionCompletionCallbackDeliveryService {

	private static final Logger log = LoggerFactory.getLogger(QuestionCompletionCallbackDeliveryService.class);

	private final QuestionCompletionCallbackRepository repository;
	private final QuestionCompletionCallbackClient client;

	public QuestionCompletionCallbackDeliveryService(
		QuestionCompletionCallbackRepository repository,
		QuestionCompletionCallbackClient client
	) {
		this.repository = repository;
		this.client = client;
	}

	public CallbackDeliveryResult deliver(long questionId) {
		Optional<PendingQuestionCompletion> pending = repository.findPending(questionId);
		if (pending.isEmpty()) {
			return CallbackDeliveryResult.NOT_PENDING;
		}

		PendingQuestionCompletion completion = pending.orElseThrow();
		CallbackHttpResult result = client.deliver(completion.questionId(), completion.answerId());
		return switch (result) {
			case DELIVERED -> CallbackDeliveryResult.DELIVERED;
			case FAILED -> CallbackDeliveryResult.FAILED;
			case NOT_FOUND -> resolveNotFound(questionId);
		};
	}

	private CallbackDeliveryResult resolveNotFound(long questionId) {
		if (!repository.existsByQuestionId(questionId)) {
			log.info("Completion callback target returned 404 after task deletion questionId={}", questionId);
			return CallbackDeliveryResult.NOT_FOUND_TASK_MISSING;
		}
		log.warn("Completion callback target returned 404 for an existing task questionId={}", questionId);
		return CallbackDeliveryResult.NOT_FOUND_TASK_EXISTS;
	}
}
