package shinhan.fibri.ieum.ai.question.service;

import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;

@FunctionalInterface
public interface QuestionAnswerOrchestrator {

	void process(ClaimedQuestionTask task);
}
