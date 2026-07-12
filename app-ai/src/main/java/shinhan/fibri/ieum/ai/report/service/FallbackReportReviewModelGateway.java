package shinhan.fibri.ieum.ai.report.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import shinhan.fibri.ieum.ai.report.domain.ReportModelReviewOutput;
import shinhan.fibri.ieum.ai.report.domain.ReportPolicySnapshot;

public class FallbackReportReviewModelGateway implements ReportReviewModelGateway {

	private final ReportReviewModelProvider primaryProvider;
	private final ReportReviewModelProvider fallbackProvider;
	private final String promptVersion;

	public FallbackReportReviewModelGateway(
		ReportReviewModelProvider primaryProvider,
		ReportReviewModelProvider fallbackProvider,
		String promptVersion
	) {
		this.primaryProvider = Objects.requireNonNull(primaryProvider, "primaryProvider must not be null");
		this.fallbackProvider = Objects.requireNonNull(fallbackProvider, "fallbackProvider must not be null");
		if (promptVersion == null || promptVersion.isBlank()) {
			throw new IllegalArgumentException("promptVersion must not be blank");
		}
		this.promptVersion = promptVersion;
	}

	@Override
	public ReportReviewInference review(
		PreparedReportReview preparedReview,
		ReportPolicySnapshot policySnapshot,
		ReportReviewModelOutputValidator outputValidator
	) {
		Objects.requireNonNull(preparedReview, "preparedReview must not be null");
		Objects.requireNonNull(policySnapshot, "policySnapshot must not be null");
		Objects.requireNonNull(outputValidator, "outputValidator must not be null");

		List<ReportReviewProviderAttempt> attempts = new ArrayList<>();
		ProviderResult primaryResult = invoke(primaryProvider, preparedReview, policySnapshot, outputValidator, attempts);
		if (primaryResult != null) {
			return inference(primaryResult, false, attempts);
		}

		ProviderResult fallbackResult = invoke(fallbackProvider, preparedReview, policySnapshot, outputValidator, attempts);
		if (fallbackResult != null) {
			return inference(fallbackResult, true, attempts);
		}
		throw new ReportReviewModelGatewayException();
	}

	private ProviderResult invoke(
		ReportReviewModelProvider provider,
		PreparedReportReview preparedReview,
		ReportPolicySnapshot policySnapshot,
		ReportReviewModelOutputValidator outputValidator,
		List<ReportReviewProviderAttempt> attempts
	) {
		long startedAt = System.nanoTime();
		try {
			ReportModelReviewOutput output = provider.review(preparedReview, policySnapshot);
			if (output == null) {
				throw new InvalidReportModelOutputException("Model output must not be null");
			}
			ReportPolicyEvaluationResult evaluation = outputValidator.evaluate(output);
			attempts.add(successAttempt(provider, startedAt));
			return new ProviderResult(evaluation, provider.model());
		} catch (InvalidReportModelOutputException exception) {
			attempts.add(failureAttempt(provider, ReportReviewProviderErrorCode.invalid_output, startedAt));
			return null;
		} catch (ReportReviewModelProviderException exception) {
			attempts.add(failureAttempt(provider, exception.errorCode(), startedAt));
			return null;
		}
	}

	private ReportReviewInference inference(
		ProviderResult result,
		boolean fallbackUsed,
		List<ReportReviewProviderAttempt> attempts
	) {
		return new ReportReviewInference(result.evaluation(), result.model(), promptVersion, fallbackUsed, attempts);
	}

	private ReportReviewProviderAttempt successAttempt(ReportReviewModelProvider provider, long startedAt) {
		return new ReportReviewProviderAttempt(provider.provider(), provider.model(), "success", null, elapsedMillis(startedAt));
	}

	private ReportReviewProviderAttempt failureAttempt(
		ReportReviewModelProvider provider,
		ReportReviewProviderErrorCode errorCode,
		long startedAt
	) {
		return new ReportReviewProviderAttempt(provider.provider(), provider.model(), "failure", errorCode, elapsedMillis(startedAt));
	}

	private long elapsedMillis(long startedAt) {
		return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
	}

	private record ProviderResult(ReportPolicyEvaluationResult evaluation, String model) {
	}
}
