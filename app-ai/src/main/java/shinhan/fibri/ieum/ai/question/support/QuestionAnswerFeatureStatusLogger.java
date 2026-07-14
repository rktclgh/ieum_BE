package shinhan.fibri.ieum.ai.question.support;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class QuestionAnswerFeatureStatusLogger {

	private static final Logger log = LoggerFactory.getLogger(QuestionAnswerFeatureStatusLogger.class);

	private final boolean questionAnswerEnabled;

	public QuestionAnswerFeatureStatusLogger(
		@Value("${app.ai.features.question-answer-enabled:false}") boolean questionAnswerEnabled
	) {
		this.questionAnswerEnabled = questionAnswerEnabled;
	}

	@EventListener(ApplicationReadyEvent.class)
	public void logStatus() {
		log.info("event=question_answer_feature_status enabled={}", questionAnswerEnabled);
	}
}
