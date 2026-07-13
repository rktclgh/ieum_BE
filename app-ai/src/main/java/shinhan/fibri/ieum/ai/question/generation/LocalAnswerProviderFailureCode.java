package shinhan.fibri.ieum.ai.question.generation;

public enum LocalAnswerProviderFailureCode {
	timeout,
	rate_limited,
	provider_unavailable,
	invalid_output,
	empty_response
}
