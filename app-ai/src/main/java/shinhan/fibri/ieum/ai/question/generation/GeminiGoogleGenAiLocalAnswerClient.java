package shinhan.fibri.ieum.ai.question.generation;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.Part;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

final class GeminiGoogleGenAiLocalAnswerClient implements GeminiLocalAnswerClient {

	private final Client client;

	GeminiGoogleGenAiLocalAnswerClient(Client client) {
		this.client = Objects.requireNonNull(client, "client must not be null");
	}

	@Override
	public GeminiLocalAnswerClientResponse generate(GeminiLocalAnswerRequest request) {
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
			return new GeminiLocalAnswerClientResponse(
				response.text(),
				usage == null ? null : usage.promptTokenCount().orElse(null),
				usage == null ? null : usage.candidatesTokenCount().orElse(null),
				response.responseId().orElse(null)
			);
		}
		catch (ApiException exception) {
			throw new GeminiLocalAnswerClientException(map(exception));
		}
		catch (GenAiIOException exception) {
			throw failure(exception);
		}
	}

	static GeminiLocalAnswerClientException failure(GenAiIOException exception) {
		Objects.requireNonNull(exception, "exception must not be null");
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable current = exception;
		while (current != null && visited.add(current)) {
			if (current instanceof SocketTimeoutException
				|| current instanceof InterruptedIOException
				|| current instanceof TimeoutException) {
				return new GeminiLocalAnswerClientException(LocalAnswerProviderFailureCode.timeout);
			}
			current = current.getCause();
		}
		return new GeminiLocalAnswerClientException(LocalAnswerProviderFailureCode.provider_unavailable);
	}

	static GenerateContentConfig config(GeminiLocalAnswerRequest request) {
		if (request.googleSearchGroundingEnabled()) {
			throw new IllegalArgumentException("Google Search grounding must remain disabled for local answers");
		}
		return GenerateContentConfig.builder()
			.systemInstruction(Content.fromParts(Part.fromText(request.systemInstruction())))
			.temperature(request.temperature())
			.maxOutputTokens(request.maxOutputTokens())
			.responseMimeType(request.responseMimeType())
			.responseJsonSchema(request.responseJsonSchema())
			.build();
	}

	private LocalAnswerProviderFailureCode map(ApiException exception) {
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

	private String safe(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}

	private boolean containsAny(String status, String message, String... values) {
		for (String value : values) {
			if (status.contains(value) || message.contains(value)) {
				return true;
			}
		}
		return false;
	}
}
