package shinhan.fibri.ieum.ai.question.webgrounding;

import java.time.Duration;
import java.util.Optional;

public final class DisabledWebGroundingGateway implements WebGroundingGateway {

	@Override
	public boolean enabled() {
		return false;
	}

	@Override
	public Optional<WebGroundedAnswer> ground(WebGroundingPrompt prompt, Duration timeout) {
		throw new IllegalStateException("Web grounding is disabled");
	}
}
