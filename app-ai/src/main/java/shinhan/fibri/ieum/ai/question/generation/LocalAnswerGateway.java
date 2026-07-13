package shinhan.fibri.ieum.ai.question.generation;

import java.time.Duration;

@FunctionalInterface
public interface LocalAnswerGateway {

	GeneratedAnswer generate(LocalAnswerPrompt prompt, Duration timeout);
}
