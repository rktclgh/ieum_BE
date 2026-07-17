package shinhan.fibri.ieum.ai.question.generation;

import java.time.Duration;
import shinhan.fibri.ieum.ai.question.webgrounding.WebGroundingPrompt;

@FunctionalInterface
public interface UngroundedAnswerGateway {

	UngroundedAnswer generate(WebGroundingPrompt prompt, Duration timeout);
}
