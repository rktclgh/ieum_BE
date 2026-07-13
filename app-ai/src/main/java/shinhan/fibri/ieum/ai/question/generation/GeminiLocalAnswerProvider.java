package shinhan.fibri.ieum.ai.question.generation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

final class GeminiLocalAnswerProvider implements LocalAnswerProvider {

	private static final String PROVIDER = "gemini";
	private static final String JSON_MIME_TYPE = "application/json";
	private static final float TEMPERATURE = 0.0f;
	private static final Map<String, Object> RESPONSE_JSON_SCHEMA = responseJsonSchema();

	private final GeminiLocalAnswerClient client;
	private final LocalAnswerPromptFactory promptFactory;
	private final LocalAnswerProperties properties;
	private final Clock clock;

	GeminiLocalAnswerProvider(
		GeminiLocalAnswerClient client,
		LocalAnswerPromptFactory promptFactory,
		LocalAnswerProperties properties,
		Clock clock
	) {
		this.client = Objects.requireNonNull(client, "client must not be null");
		this.promptFactory = Objects.requireNonNull(promptFactory, "promptFactory must not be null");
		this.properties = Objects.requireNonNull(properties, "properties must not be null");
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	@Override
	public String provider() {
		return PROVIDER;
	}

	@Override
	public String model() {
		return properties.fallbackModel();
	}

	@Override
	public LocalAnswerProviderResponse generate(LocalAnswerPrompt prompt) {
		LocalAnswerModelPrompt modelPrompt = promptFactory.create(prompt);
		Instant startedAt = clock.instant();
		try {
			GeminiLocalAnswerClientResponse response = client.generate(new GeminiLocalAnswerRequest(
				properties.fallbackModel(),
				modelPrompt.systemInstruction(),
				modelPrompt.userInstruction(),
				JSON_MIME_TYPE,
				RESPONSE_JSON_SCHEMA,
				TEMPERATURE,
				properties.maxTokens(),
				false
			));
			if (response == null) {
				throw new LocalAnswerProviderException(LocalAnswerProviderFailureCode.empty_response);
			}
			return new LocalAnswerProviderResponse(
				response.rawOutput(),
				startedAt,
				response.inputTokenCount(),
				response.outputTokenCount(),
				response.providerRequestId()
			);
		}
		catch (LocalAnswerProviderException exception) {
			throw exception;
		}
		catch (GeminiLocalAnswerClientException exception) {
			throw new LocalAnswerProviderException(exception.failureCode());
		}
		catch (RuntimeException exception) {
			throw new LocalAnswerProviderException(LocalAnswerProviderFailureCode.provider_unavailable);
		}
	}

	private static Map<String, Object> responseJsonSchema() {
		Map<String, Object> integer = Map.of("type", "integer", "minimum", 0);
		Map<String, Object> citation = Map.of(
			"type", "object",
			"additionalProperties", false,
			"required", List.of("evidenceIndex", "startIndex", "endIndex"),
			"properties", Map.of(
				"evidenceIndex", integer,
				"startIndex", integer,
				"endIndex", Map.of("type", "integer", "minimum", 1)
			)
		);
		return Map.of(
			"type", "object",
			"additionalProperties", false,
			"required", List.of("answer", "citations"),
			"properties", Map.of(
				"answer", Map.of("type", "string", "minLength", 1),
				"citations", Map.of(
					"type", "array",
					"minItems", 1,
					"maxItems", 8,
					"items", citation
				)
			)
		);
	}
}
