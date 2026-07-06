package shinhan.fibri.ieum.main.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;
import shinhan.fibri.ieum.main.auth.dto.SignupRequest;
import shinhan.fibri.ieum.main.auth.exception.EmailNotVerifiedException;
import shinhan.fibri.ieum.main.auth.exception.InvalidCredentialsException;
import shinhan.fibri.ieum.main.auth.exception.InvalidRefreshTokenException;
import shinhan.fibri.ieum.main.auth.exception.RefreshTokenReusedException;
import shinhan.fibri.ieum.main.auth.exception.SuspendedUserException;

import static org.assertj.core.api.Assertions.assertThat;

class AuthExceptionHandlerTest {

	private final AuthExceptionHandler handler = new AuthExceptionHandler();

	@Test
	void handleValidationFailureIncludesGlobalErrors() throws Exception {
		SignupRequest request = new SignupRequest(null, null, null, null, null, null, null, null);
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

	@Test
	void handleInvalidCredentialsReturnsUnauthorized() {
		var response = handler.handleInvalidCredentials(new InvalidCredentialsException());

		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody()).isEqualTo(new AuthErrorResponse(
			"INVALID_CREDENTIALS",
			"Invalid email or password"
		));
	}

	@Test
	void handleEmailNotVerifiedReturnsForbidden() {
		var response = handler.handleEmailNotVerified(new EmailNotVerifiedException());

		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody()).isEqualTo(new AuthErrorResponse(
			"EMAIL_NOT_VERIFIED",
			"Email is not verified"
		));
	}

	@Test
	void handleSuspendedUserReturnsForbidden() {
		var response = handler.handleSuspendedUser(new SuspendedUserException());

		assertThat(response.getStatusCode().value()).isEqualTo(403);
		assertThat(response.getBody()).isEqualTo(new AuthErrorResponse(
			"SUSPENDED_USER",
			"User is suspended"
		));
	}

	@Test
	void handleInvalidRefreshTokenReturnsUnauthorized() {
		var response = handler.handleInvalidRefreshToken(new InvalidRefreshTokenException());

		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody()).isEqualTo(new AuthErrorResponse(
			"INVALID_REFRESH_TOKEN",
			"Invalid refresh token"
		));
	}

	@Test
	void handleRefreshTokenReusedReturnsUnauthorized() {
		var response = handler.handleRefreshTokenReused(new RefreshTokenReusedException());

		assertThat(response.getStatusCode().value()).isEqualTo(401);
		assertThat(response.getBody()).isEqualTo(new AuthErrorResponse(
			"REFRESH_TOKEN_REUSED",
			"Refresh token was reused"
		));
	}

	@SuppressWarnings("unused")
	void submit(SignupRequest request) {
	}
}
