package shinhan.fibri.ieum.ai.question.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class QuestionAnswerFeatureStatusLoggerTest {

	@Test
	void logsFeatureStatusWhenQuestionAnswerIsDisabled(CapturedOutput output) {
		new QuestionAnswerFeatureStatusLogger(false).logStatus();

		assertThat(output)
			.contains("event=question_answer_feature_status")
			.contains("enabled=false");
	}

	@Test
	void logsFeatureStatusWhenQuestionAnswerIsEnabled(CapturedOutput output) {
		new QuestionAnswerFeatureStatusLogger(true).logStatus();

		assertThat(output)
			.contains("event=question_answer_feature_status")
			.contains("enabled=true");
	}
}
