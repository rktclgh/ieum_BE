package shinhan.fibri.ieum.main.pin.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.core.MethodParameter;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import shinhan.fibri.ieum.main.auth.dto.AuthErrorResponse;

class PinExceptionHandlerTest {

	private final PinExceptionHandler handler = new PinExceptionHandler();

	@Test
	void handleMethodValidationFallsBackWhenParameterNameIsNull() {
		MethodParameter methodParameter = mock(MethodParameter.class);
		when(methodParameter.getParameterName()).thenReturn(null);
		MessageSourceResolvable error = mock(MessageSourceResolvable.class);
		when(error.getDefaultMessage()).thenReturn("invalid parameter");
		ParameterValidationResult result = new ParameterValidationResult(
			methodParameter,
			null,
			List.of(error),
			null,
			null,
			null,
			null
		);
		HandlerMethodValidationException exception = mock(HandlerMethodValidationException.class);
		when(exception.getParameterValidationResults()).thenReturn(List.of(result));

		ResponseEntity<AuthErrorResponse> response = handler.handleMethodValidation(exception);

		assertThat(response.getStatusCode().value()).isEqualTo(400);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().fieldErrors())
			.containsExactly(new AuthErrorResponse.FieldError("parameter", "invalid parameter"));
	}
}
