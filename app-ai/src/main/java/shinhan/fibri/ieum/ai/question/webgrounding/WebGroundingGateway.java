package shinhan.fibri.ieum.ai.question.webgrounding;

import java.time.Duration;
import java.util.Optional;

public interface WebGroundingGateway {

	boolean enabled();

	Optional<WebGroundedAnswer> ground(WebGroundingPrompt prompt, Duration timeout);
}
