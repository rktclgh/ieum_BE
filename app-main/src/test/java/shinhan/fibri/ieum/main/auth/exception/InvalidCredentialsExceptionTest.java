package shinhan.fibri.ieum.main.auth.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class InvalidCredentialsExceptionTest {

	@Test
	void constructorUsesExplicitMessage() {
		InvalidCredentialsException exception = new InvalidCredentialsException("Admin login rejected");

		assertThat(exception).hasMessage("Admin login rejected");
	}
}
