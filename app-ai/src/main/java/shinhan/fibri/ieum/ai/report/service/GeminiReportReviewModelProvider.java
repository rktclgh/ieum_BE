package shinhan.fibri.ieum.ai.report.service;

import com.google.genai.Client;
import com.google.genai.errors.ApiException;
import com.google.genai.errors.GenAiIOException;
import com.google.genai.types.BlockedReason;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import shinhan.fibri.ieum.ai.report.domain.ReportModelReviewOutput;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;

public class GeminiReportReviewModelProvider implements ReportReviewModelProvider {

	private static final String PROVIDER = "gemini";
	private static final String JSON_MIME_TYPE = "application/json";

	private final String model;
	private final GeminiReportReviewClient client;
	private final ReportReviewModelPromptFactory promptFactory;
	private final ReportReviewModelOutputParser outputParser;

	public GeminiReportReviewModelProvider(
		String model,
		GeminiReportReviewClient client,
		ReportReviewModelPromptFactory promptFactory,
		ReportReviewModelOutputParser outputParser
	) {
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("model must not be blank");
		}
		this.model = model.trim();
		this.client = Objects.requireNonNull(client, "client must not be null");
		this.promptFactory = Objects.requireNonNull(promptFactory, "promptFactory must not be null");
		this.outputParser = Objects.requireNonNull(outputParser, "outputParser must not be null");
	}

	public static GeminiReportReviewModelProvider googleGenAi(
		String apiKey,
		String model,
		ReportReviewModelPromptFactory promptFactory,
		ReportReviewModelOutputParser outputParser
	) {
		return new GeminiReportReviewModelProvider(
			model,
			new GeminiGoogleGenAiReportReviewClient(apiKey),
			promptFactory,
			outputParser
		);
	}

	public static GeminiReportReviewModelProvider googleGenAi(
		Client client,
		String model,
		ReportReviewModelPromptFactory promptFactory,
		ReportReviewModelOutputParser outputParser
	) {
		return new GeminiReportReviewModelProvider(
			model,
			new GeminiGoogleGenAiReportReviewClient(client),
			promptFactory,
			outputParser
		);
	}

	@Override
	public String provider() {
		return PROVIDER;
	}

	@Override
	public String model() {
		return model;
	}

	@Override
	public ReportModelReviewOutput review(PreparedReportReview preparedReview, ReportPolicySnapshot policySnapshot) {
		ReportReviewModelPrompt prompt = promptFactory.create(preparedReview, policySnapshot);
		String rawOutput;
		try {
			rawOutput = client.generate(new GeminiReportReviewRequest(
				model,
				prompt.systemInstruction(),
				prompt.userInstruction(),
				prompt.images(),
				JSON_MIME_TYPE,
				false
			));
		} catch (GeminiReportReviewClientException exception) {
			throw new ReportReviewModelProviderException(exception.errorCode());
		} catch (RuntimeException exception) {
			throw new ReportReviewModelProviderException(ReportReviewProviderErrorCode.transport_error);
		}
		return outputParser.parse(rawOutput);
	}
}

interface GeminiReportReviewClient {

	String generate(GeminiReportReviewRequest request);
}

record GeminiReportReviewRequest(
	String model,
	String systemInstruction,
	String userInstruction,
	List<ReportReviewModelPromptImage> images,
	String responseMimeType,
	boolean googleSearchGroundingEnabled
) {

	GeminiReportReviewRequest {
		if (model == null || model.isBlank()) {
			throw new IllegalArgumentException("model must not be blank");
		}
		if (systemInstruction == null || systemInstruction.isBlank()) {
			throw new IllegalArgumentException("systemInstruction must not be blank");
		}
		if (userInstruction == null || userInstruction.isBlank()) {
			throw new IllegalArgumentException("userInstruction must not be blank");
		}
		if (!"application/json".equals(responseMimeType)) {
			throw new IllegalArgumentException("responseMimeType must be application/json");
		}
		Objects.requireNonNull(images, "images must not be null");
		images = List.copyOf(images);
	}
}

class GeminiReportReviewClientException extends RuntimeException {

	private final ReportReviewProviderErrorCode errorCode;

	GeminiReportReviewClientException(ReportReviewProviderErrorCode errorCode) {
		super("Gemini report review client failed");
		this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
	}

	ReportReviewProviderErrorCode errorCode() {
		return errorCode;
	}
}

class GeminiGoogleGenAiReportReviewClient implements GeminiReportReviewClient {

	private final Client client;

	GeminiGoogleGenAiReportReviewClient(String apiKey) {
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalArgumentException("apiKey must not be blank");
		}
		this.client = Client.builder().apiKey(apiKey.trim()).build();
	}

	GeminiGoogleGenAiReportReviewClient(Client client) {
		this.client = Objects.requireNonNull(client, "client must not be null");
	}

	@Override
	public String generate(GeminiReportReviewRequest request) {
		try {
			GenerateContentResponse response = client.models.generateContent(
				request.model(),
				userContent(request),
				config(request)
			);
			rejectSafetyResponse(response);
			return response.text();
		} catch (GeminiReportReviewClientException exception) {
			throw exception;
		} catch (ApiException exception) {
			throw new GeminiReportReviewClientException(mapApiException(exception));
		} catch (GenAiIOException exception) {
			throw new GeminiReportReviewClientException(ReportReviewProviderErrorCode.transport_error);
		}
	}

	private Content userContent(GeminiReportReviewRequest request) {
		List<Part> parts = new ArrayList<>();
		parts.add(Part.fromText(request.userInstruction()));
		for (ReportReviewModelPromptImage image : request.images()) {
			parts.add(Part.fromBytes(image.bytes(), image.contentType()));
		}
		return Content.builder()
			.role("user")
			.parts(parts)
			.build();
	}

	private GenerateContentConfig config(GeminiReportReviewRequest request) {
		GenerateContentConfig.Builder builder = GenerateContentConfig.builder()
			.systemInstruction(Content.fromParts(Part.fromText(request.systemInstruction())))
			.responseMimeType(request.responseMimeType());
		if (request.googleSearchGroundingEnabled()) {
			throw new IllegalArgumentException("Google Search grounding is not supported for report review");
		}
		return builder.build();
	}

	private void rejectSafetyResponse(GenerateContentResponse response) {
		Optional<BlockedReason> blockedReason = response.promptFeedback()
			.flatMap(feedback -> feedback.blockReason());
		if (blockedReason.isPresent() && isSafetyBlockedReason(blockedReason.get())) {
			throw new GeminiReportReviewClientException(ReportReviewProviderErrorCode.safety_refusal);
		}
		for (Candidate candidate : response.candidates().orElse(List.of())) {
			Optional<FinishReason> finishReason = candidate.finishReason();
			if (finishReason.isPresent() && isSafetyFinishReason(finishReason.get())) {
				throw new GeminiReportReviewClientException(ReportReviewProviderErrorCode.safety_refusal);
			}
		}
	}

	private boolean isSafetyBlockedReason(BlockedReason reason) {
		BlockedReason.Known knownReason = reason.knownEnum();
		if (knownReason == null) {
			return false;
		}
		return switch (knownReason) {
			case SAFETY, BLOCKLIST, PROHIBITED_CONTENT, IMAGE_SAFETY, MODEL_ARMOR, JAILBREAK ->
				true;
			default -> false;
		};
	}

	private boolean isSafetyFinishReason(FinishReason reason) {
		FinishReason.Known knownReason = reason.knownEnum();
		if (knownReason == null) {
			return false;
		}
		return switch (knownReason) {
			case SAFETY, BLOCKLIST, PROHIBITED_CONTENT, SPII, IMAGE_SAFETY, IMAGE_PROHIBITED_CONTENT ->
				true;
			default -> false;
		};
	}

	private ReportReviewProviderErrorCode mapApiException(ApiException exception) {
		int code = exception.code();
		String status = safe(exception.status());
		String message = safe(exception.message());
		if (code == 408 || code == 504 || containsAny(status, message, "timeout", "deadline")) {
			return ReportReviewProviderErrorCode.timeout;
		}
		if (code == 429 || containsAny(status, message, "quota", "rate")) {
			return ReportReviewProviderErrorCode.rate_limited;
		}
		if (containsAny(status, message, "safety", "blocked", "prohibited")) {
			return ReportReviewProviderErrorCode.safety_refusal;
		}
		if (code >= 500) {
			return ReportReviewProviderErrorCode.server_error;
		}
		return ReportReviewProviderErrorCode.transport_error;
	}

	private String safe(String value) {
		return value == null ? "" : value.toLowerCase(Locale.ROOT);
	}

	private boolean containsAny(String status, String message, String... needles) {
		for (String needle : needles) {
			if (status.contains(needle) || message.contains(needle)) {
				return true;
			}
		}
		return false;
	}
}
