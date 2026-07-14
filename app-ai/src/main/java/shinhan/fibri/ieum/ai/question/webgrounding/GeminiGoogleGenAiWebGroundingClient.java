package shinhan.fibri.ieum.ai.question.webgrounding;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GoogleSearch;
import com.google.genai.types.Part;
import com.google.genai.types.Tool;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;

final class GeminiGoogleGenAiWebGroundingClient implements GeminiWebGroundingClient {

	private final Client client;

	GeminiGoogleGenAiWebGroundingClient(Client client) {
		this.client = Objects.requireNonNull(client, "client must not be null");
	}

	@Override
	public GenerateContentResponse generate(GeminiWebGroundingRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		try {
			return client.models.generateContent(
				request.model(),
				Content.fromParts(Part.fromText(request.userInstruction())),
				config(request)
			);
		}
		catch (ApiException exception) {
			throw new QuestionWebGroundingUnavailableException(map(exception));
		}
		catch (GenAiIOException exception) {
			throw failure(exception);
		}
	}

	static GenerateContentConfig config(GeminiWebGroundingRequest request) {
		Objects.requireNonNull(request, "request must not be null");
		return GenerateContentConfig.builder()
			.systemInstruction(Content.fromParts(Part.fromText(request.systemInstruction())))
			.temperature(0.0f)
			.candidateCount(1)
			.maxOutputTokens(request.maxOutputTokens())
			.responseMimeType("text/plain")
			.tools(Tool.builder().googleSearch(GoogleSearch.builder().build()).build())
			.build();
	}

	static WebGroundingFailureCode map(ApiException exception) {
		int code = Objects.requireNonNull(exception, "exception must not be null").code();
		if (code == 408 || code == 504) {
			return WebGroundingFailureCode.timeout;
		}
		if (code == 429) {
			return WebGroundingFailureCode.rate_limited;
		}
		if (code >= 500 && code <= 599) {
			return WebGroundingFailureCode.provider_unavailable;
		}
		if (code >= 400 && code <= 499) {
			return WebGroundingFailureCode.permanent_configuration;
		}
		return WebGroundingFailureCode.provider_unavailable;
	}

	static QuestionWebGroundingUnavailableException failure(GenAiIOException exception) {
		Objects.requireNonNull(exception, "exception must not be null");
		Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
		Throwable current = exception;
		while (current != null && visited.add(current)) {
			if (current instanceof SocketTimeoutException
				|| current instanceof InterruptedIOException
				|| current instanceof TimeoutException) {
				return new QuestionWebGroundingUnavailableException(WebGroundingFailureCode.timeout);
			}
			current = current.getCause();
		}
		return new QuestionWebGroundingUnavailableException(
			WebGroundingFailureCode.provider_unavailable
		);
	}
}
