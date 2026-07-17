package shinhan.fibri.ieum.ai.question.finalization;

import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QuestionAnswerFinalizationService {

	private final JdbcQuestionAnswerFinalizationRepository repository;

	public QuestionAnswerFinalizationService(JdbcQuestionAnswerFinalizationRepository repository) {
		this.repository = repository;
	}

	@Transactional
	public QuestionAnswerFinalizationResult completeGrounded(
		GroundedQuestionAnswerFinalization command
	) {
		Objects.requireNonNull(command, "command must not be null");
		QuestionTaskFence fence = command.fence();
		lockCurrentFence(fence);
		if (!repository.lockActiveQuestion(fence.questionId())) {
			cancelCurrentFence(fence);
			return new QuestionAnswerFinalizationResult(fence.questionId(), null);
		}

		long answerId = repository.insertAnswerIfActive(fence.questionId(), command.content());
		if (!repository.completeGrounded(command, answerId)) {
			throw stale(fence);
		}
		return new QuestionAnswerFinalizationResult(fence.questionId(), answerId);
	}

	@Transactional
	public QuestionAnswerFinalizationResult completeInsufficient(
		InsufficientQuestionAnswerFinalization command
	) {
		Objects.requireNonNull(command, "command must not be null");
		QuestionTaskFence fence = command.fence();
		lockCurrentFence(fence);
		if (!repository.lockActiveQuestion(fence.questionId())) {
			cancelCurrentFence(fence);
			return new QuestionAnswerFinalizationResult(fence.questionId(), null);
		}
		if (!repository.completeInsufficient(command)) {
			throw stale(fence);
		}
		return new QuestionAnswerFinalizationResult(fence.questionId(), null);
	}

	@Transactional
	public QuestionAnswerFinalizationResult completeUngrounded(
		UngroundedQuestionAnswerFinalization command
	) {
		Objects.requireNonNull(command, "command must not be null");
		QuestionTaskFence fence = command.fence();
		lockCurrentFence(fence);
		if (!repository.lockActiveQuestion(fence.questionId())) {
			cancelCurrentFence(fence);
			return new QuestionAnswerFinalizationResult(fence.questionId(), null);
		}

		long answerId = repository.insertAnswerIfActive(fence.questionId(), command.content());
		if (!repository.completeUngrounded(command, answerId)) {
			throw stale(fence);
		}
		return new QuestionAnswerFinalizationResult(fence.questionId(), answerId);
	}

	private void lockCurrentFence(QuestionTaskFence fence) {
		if (!repository.lockCurrentFence(fence)) {
			throw stale(fence);
		}
	}

	private void cancelCurrentFence(QuestionTaskFence fence) {
		if (!repository.cancelCurrentFence(fence)) {
			throw stale(fence);
		}
	}

	private StaleQuestionTaskFinalizationException stale(QuestionTaskFence fence) {
		return new StaleQuestionTaskFinalizationException(fence.questionId());
	}
}
