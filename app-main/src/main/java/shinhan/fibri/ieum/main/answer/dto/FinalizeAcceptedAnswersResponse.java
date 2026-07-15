package shinhan.fibri.ieum.main.answer.dto;

import java.util.List;

public record FinalizeAcceptedAnswersResponse(
	Long questionId,
	boolean answerSelectionFinalized,
	List<Long> acceptedAnswerIds
) {
}
