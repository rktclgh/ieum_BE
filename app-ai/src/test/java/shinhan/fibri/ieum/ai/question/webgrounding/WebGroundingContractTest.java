package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class WebGroundingContractTest {

	@Test
	void exposesTheProviderNeutralGatewayContract() throws Exception {
		Method enabled = WebGroundingGateway.class.getDeclaredMethod("enabled");
		Method ground = WebGroundingGateway.class.getDeclaredMethod(
			"ground",
			WebGroundingPrompt.class,
			Duration.class
		);

		assertThat(enabled.getReturnType()).isEqualTo(boolean.class);
		assertThat(ground.getReturnType()).isEqualTo(Optional.class);
		assertThat(ground.getGenericReturnType().getTypeName())
			.isEqualTo("java.util.Optional<shinhan.fibri.ieum.ai.question.webgrounding.WebGroundedAnswer>");
	}

	@Test
	void disabledGatewayReportsDisabledAndFailsEveryGroundAttemptImmediately() {
		WebGroundingGateway gateway = new DisabledWebGroundingGateway();

		assertThat(gateway.enabled()).isFalse();
		assertThatThrownBy(() -> gateway.ground(
			new WebGroundingPrompt("질문", "내용", WebGroundingRegion.empty()),
			Duration.ofSeconds(45)
		)).isInstanceOf(IllegalStateException.class)
			.hasMessage("Web grounding is disabled")
			.hasNoCause();
		assertThatThrownBy(() -> gateway.ground(null, null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Web grounding is disabled")
			.hasNoCause();
	}

	@Test
	void failureCodesAreClosedToTheDocumentedSafeValues() {
		assertThat(WebGroundingFailureCode.values()).containsExactly(
			WebGroundingFailureCode.timeout,
			WebGroundingFailureCode.rate_limited,
			WebGroundingFailureCode.provider_unavailable,
			WebGroundingFailureCode.permanent_configuration
		);
	}

	@Test
	void unavailableExceptionPreservesOnlyItsSafeFailureCode() {
		QuestionWebGroundingUnavailableException exception =
			new QuestionWebGroundingUnavailableException(WebGroundingFailureCode.rate_limited);

		assertThat(exception.failureCode()).isEqualTo(WebGroundingFailureCode.rate_limited);
		assertThat(exception)
			.hasMessage("Question web grounding is unavailable")
			.hasNoCause();
		assertThat(exception.getMessage()).doesNotContain("quota", "provider", "api key", "prompt");

		Constructor<?>[] constructors = QuestionWebGroundingUnavailableException.class.getDeclaredConstructors();
		assertThat(constructors).singleElement().satisfies(constructor ->
			assertThat(constructor.getParameterTypes()).containsExactly(WebGroundingFailureCode.class)
		);
	}

	@Test
	void unavailableExceptionRejectsMissingFailureCode() {
		assertThatThrownBy(() -> new QuestionWebGroundingUnavailableException(null))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("failureCode");
	}
}
