package shinhan.fibri.ieum.ai.question.webgrounding;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public final class GeminiSearchWebGroundingGateway implements WebGroundingGateway {

	private final GeminiWebGroundingClient client;
	private final GeminiWebGroundingModelPromptFactory promptFactory;
	private final GeminiWebGroundingResponseParser parser;
	private final WebGroundingProperties properties;
	private final Clock clock;

	GeminiSearchWebGroundingGateway(
		GeminiWebGroundingClient client,
		GeminiWebGroundingModelPromptFactory promptFactory,
		GeminiWebGroundingResponseParser parser,
		WebGroundingProperties properties,
		Clock clock
	) {
		this.client = Objects.requireNonNull(client, "client must not be null");
		this.promptFactory = Objects.requireNonNull(
			promptFactory,
			"promptFactory must not be null"
		);
		this.parser = Objects.requireNonNull(parser, "parser must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	@Override
	public boolean enabled() {
		return true;
	}

	@Override
	public Optional<WebGroundedAnswer> ground(
		WebGroundingPrompt prompt,
		Duration timeout
	) {
		Objects.requireNonNull(prompt, "prompt must not be null");
		Objects.requireNonNull(timeout, "timeout must not be null");
		if (!properties.modelTimeout().equals(timeout)) {
			throw new IllegalArgumentException("timeout must match the configured model timeout");
		}

		GeminiWebGroundingRequest request = promptFactory.create(prompt, properties);
		Instant generatedAt = clock.instant();
		return parser.parse(client.generate(request), properties, generatedAt);
	}
}
