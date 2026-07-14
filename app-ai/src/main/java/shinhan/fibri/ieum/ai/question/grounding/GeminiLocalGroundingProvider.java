package shinhan.fibri.ieum.ai.question.grounding;

import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProperties;
import shinhan.fibri.ieum.ai.question.generation.LocalAnswerProviderFailureCode;

final class GeminiLocalGroundingProvider implements LocalGroundingProvider {

	private static final String PROVIDER = "gemini";
	private static final String JSON_MIME_TYPE = "application/json";
	private static final float TEMPERATURE = 0.0f;
	private static final Map<String, Object> VALIDATION_SCHEMA = validationSchema();
	private static final Map<String, Object> REPAIR_SCHEMA = repairSchema();

	private final Client client;
	private final LocalAnswerProperties answerProperties;
	private final LocalGroundingProperties groundingProperties;
	private final Clock clock;

	GeminiLocalGroundingProvider(
		Client client,
		LocalAnswerProperties answerProperties,
		LocalGroundingProperties groundingProperties,
		Clock clock
	) {
		this.client = Objects.requireNonNull(client, "client must not be null");
		this.answerProperties = Objects.requireNonNull(answerProperties, "answerProperties must not be null");
		this.groundingProperties = Objects.requireNonNull(
			groundingProperties,
			"groundingProperties must not be null"
		);
		this.clock = Objects.requireNonNull(clock, "clock must not be null");
	}

	@Override
	public String provider() {
		return PROVIDER;
	}

	@Override
	public String model() {
		return answerProperties.fallbackModel();
	}

	@Override
	public GroundingProviderResponse validate(GroundingModelPrompt prompt) {
		return invoke(prompt, VALIDATION_SCHEMA, groundingProperties.validationMaxTokens());
	}

	@Override
	public GroundingProviderResponse repair(GroundingModelPrompt prompt) {
		return invoke(prompt, REPAIR_SCHEMA, groundingProperties.repairMaxTokens());
	}

	private GroundingProviderResponse invoke(
		GroundingModelPrompt prompt,
		Map<String, Object> responseJsonSchema,
		int maxOutputTokens
	) {
		Objects.requireNonNull(prompt, "prompt must not be null");
		Instant startedAt = clock.instant();
		try {
			ClientResponse response = client.generate(new ClientRequest(
				answerProperties.fallbackModel(),
				prompt.systemInstruction(),
				prompt.userInstruction(),
				JSON_MIME_TYPE,
				responseJsonSchema,
				TEMPERATURE,
				maxOutputTokens,
				false
			));
			if (response == null) {
				throw new GroundingProviderException(LocalAnswerProviderFailureCode.empty_response);
			}
			return new GroundingProviderResponse(
				response.rawOutput(),
				startedAt,
				response.inputTokenCount(),
				response.outputTokenCount(),
				response.providerRequestId()
			);
		}
		catch (GroundingProviderException exception) {
			throw exception;
		}
		catch (ClientException exception) {
			throw new GroundingProviderException(exception.failureCode());
		}
		catch (RuntimeException exception) {
			throw new GroundingProviderException(LocalAnswerProviderFailureCode.provider_unavailable);
		}
	}

	private static Map<String, Object> validationSchema() {
		return Map.of(
			"type", "object",
			"additionalProperties", false,
			"required", List.of("supported", "score", "unsupportedClaims"),
			"properties", Map.of(
				"supported", Map.of("type", "boolean"),
				"score", Map.of("type", "number", "minimum", 0, "maximum", 1),
				"unsupportedClaims", Map.of(
					"type", "array",
					"minItems", 0,
					"maxItems", 8,
					"items", Map.of("type", "string", "minLength", 1, "maxLength", 500)
				)
			)
		);
	}

	private static Map<String, Object> repairSchema() {
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

	@FunctionalInterface
	interface Client {

		ClientResponse generate(ClientRequest request);
	}

	record ClientRequest(
		String model,
		String systemInstruction,
		String userInstruction,
		String responseMimeType,
		Map<String, Object> responseJsonSchema,
		float temperature,
		int maxOutputTokens,
		boolean googleSearchGroundingEnabled
	) {

		ClientRequest {
			model = required(model, "model");
			systemInstruction = required(systemInstruction, "systemInstruction");
			userInstruction = required(userInstruction, "userInstruction");
			if (!JSON_MIME_TYPE.equals(responseMimeType)) {
				throw new IllegalArgumentException("responseMimeType must be application/json");
			}
			if (responseJsonSchema == null || responseJsonSchema.isEmpty()) {
				throw new IllegalArgumentException("responseJsonSchema must not be empty");
			}
			responseJsonSchema = Map.copyOf(responseJsonSchema);
			if (!Float.isFinite(temperature) || Float.compare(temperature, TEMPERATURE) != 0) {
				throw new IllegalArgumentException("temperature must be zero");
			}
			if (maxOutputTokens <= 0) {
				throw new IllegalArgumentException("maxOutputTokens must be positive");
			}
			if (googleSearchGroundingEnabled) {
				throw new IllegalArgumentException("Google Search grounding must remain disabled");
			}
		}

		private static String required(String value, String field) {
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException(field + " must not be blank");
			}
			return value.trim();
		}
	}

	record ClientResponse(
		String rawOutput,
		Integer inputTokenCount,
		Integer outputTokenCount,
		String providerRequestId
	) {

		ClientResponse {
			inputTokenCount = nonNegative(inputTokenCount, "inputTokenCount");
			outputTokenCount = nonNegative(outputTokenCount, "outputTokenCount");
			providerRequestId = providerRequestId == null || providerRequestId.isBlank()
				? null
				: providerRequestId.trim();
		}

		private static Integer nonNegative(Integer value, String field) {
			if (value != null && value < 0) {
				throw new IllegalArgumentException(field + " must be non-negative");
			}
			return value;
		}
	}

	static final class ClientException extends RuntimeException {

		private final LocalAnswerProviderFailureCode failureCode;

		ClientException(LocalAnswerProviderFailureCode failureCode) {
			super("Gemini grounding client failed");
			this.failureCode = Objects.requireNonNull(failureCode, "failureCode must not be null");
		}

		LocalAnswerProviderFailureCode failureCode() {
			return failureCode;
		}
	}

	static final class GoogleClient implements Client {

		private final com.google.genai.Client client;

		GoogleClient(com.google.genai.Client client) {
			this.client = Objects.requireNonNull(client, "client must not be null");
		}

		@Override
		public ClientResponse generate(ClientRequest request) {
			try {
				GenerateContentResponse response = client.models.generateContent(
					request.model(),
					Content.fromParts(Part.fromText(request.userInstruction())),
					config(request)
				);
				if (response == null) {
					return null;
				}
				GenerateContentResponseUsageMetadata usage = response.usageMetadata().orElse(null);
				return new ClientResponse(
					response.text(),
					usage == null ? null : usage.promptTokenCount().orElse(null),
					usage == null ? null : usage.candidatesTokenCount().orElse(null),
					response.responseId().orElse(null)
				);
			}
			catch (ApiException exception) {
				throw new ClientException(map(exception));
			}
			catch (GenAiIOException exception) {
				throw failure(exception);
			}
		}

		static GenerateContentConfig config(ClientRequest request) {
			if (request.googleSearchGroundingEnabled()) {
				throw new IllegalArgumentException("Google Search grounding must remain disabled");
			}
			return GenerateContentConfig.builder()
				.systemInstruction(Content.fromParts(Part.fromText(request.systemInstruction())))
				.temperature(request.temperature())
				.maxOutputTokens(request.maxOutputTokens())
				.responseMimeType(request.responseMimeType())
				.responseJsonSchema(request.responseJsonSchema())
				.build();
		}

		static ClientException failure(GenAiIOException exception) {
			Objects.requireNonNull(exception, "exception must not be null");
			Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
			Throwable current = exception;
			while (current != null && visited.add(current)) {
				if (current instanceof SocketTimeoutException
					|| current instanceof InterruptedIOException
					|| current instanceof TimeoutException) {
					return new ClientException(LocalAnswerProviderFailureCode.timeout);
				}
				current = current.getCause();
			}
			return new ClientException(LocalAnswerProviderFailureCode.provider_unavailable);
		}

		private static LocalAnswerProviderFailureCode map(ApiException exception) {
			int code = exception.code();
			String status = safe(exception.status());
			String message = safe(exception.message());
			if (code == 408 || code == 504 || containsAny(status, message, "timeout", "deadline")) {
				return LocalAnswerProviderFailureCode.timeout;
			}
			if (code == 429 || containsAny(status, message, "quota", "rate")) {
				return LocalAnswerProviderFailureCode.rate_limited;
			}
			return LocalAnswerProviderFailureCode.provider_unavailable;
		}

		private static String safe(String value) {
			return value == null ? "" : value.toLowerCase(Locale.ROOT);
		}

		private static boolean containsAny(String status, String message, String... values) {
			for (String value : values) {
				if (status.contains(value) || message.contains(value)) {
					return true;
				}
			}
			return false;
		}
	}
}
