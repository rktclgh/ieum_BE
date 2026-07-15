package shinhan.fibri.ieum.main.answer.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.util.List;

public record FinalizeAcceptedAnswersRequest(
	@NotEmpty List<@NotNull @Positive Long> answerIds
) {
}
