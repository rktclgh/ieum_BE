package shinhan.fibri.ieum.ai.question.grounding;

import java.time.Duration;
import shinhan.fibri.ieum.ai.question.generation.GeneratedAnswer;

public interface LocalGroundingGateway {

	GroundingValidation validate(LocalGroundingRequest request, Duration timeout);

	GeneratedAnswer repair(
		LocalGroundingRequest request,
		GroundingValidation failedValidation,
		Duration timeout
	);
}
