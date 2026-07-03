package shinhan.fibri.ieum.main.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.auth.dto.SignupRequest;

import static org.assertj.core.api.Assertions.assertThat;

class AuthExceptionHandlerTest {

	private final AuthExceptionHandler handler = new AuthExceptionHandler();

	@Test
	void handleValidationFailureIncludesGlobalErrors() throws Exception {
		SignupRequest request = new SignupRequest(null, null, null, null, null);
		BeanPropertyBindingResult bindingResult = new BeanPropertyBindingResult(request, "signupRequest");
		bindingResult.addError(new ObjectError("signupRequest", "Cross-field validation failed"));
		MethodParameter methodParameter = new MethodParameter(
			AuthExceptionHandlerTest.class.getDeclaredMethod("submit", SignupRequest.class),
			0
		);

		AuthErrorResponse response = handler.handleValidationFailure(
			new MethodArgumentNotValidException(methodParameter, bindingResult)
		).getBody();

		assertThat(response).isNotNull();
		assertThat(response.fieldErrors())
			.anySatisfy(error -> {
				assertThat(error.field()).isEqualTo("signupRequest");
				assertThat(error.message()).isEqualTo("Cross-field validation failed");
			});
	}

	@SuppressWarnings("unused")
	void submit(SignupRequest request) {
	}
}
