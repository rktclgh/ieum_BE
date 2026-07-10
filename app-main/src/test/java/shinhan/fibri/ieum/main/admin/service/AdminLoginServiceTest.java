package shinhan.fibri.ieum.main.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.main.auth.dto.LoginRequest;
import shinhan.fibri.ieum.main.auth.dto.LoginResponse;
import shinhan.fibri.ieum.main.auth.exception.EmailNotVerifiedException;
import shinhan.fibri.ieum.main.auth.exception.InvalidCredentialsException;
import shinhan.fibri.ieum.main.auth.service.LoginResult;
import shinhan.fibri.ieum.main.auth.service.LoginService;

class AdminLoginServiceTest {

	@Test
	void loginReturnsAdminSummaryAndTokensWhenAuthenticatedUserIsAdmin() {
		LoginService loginService = mock(LoginService.class);
		AdminLoginService service = new AdminLoginService(loginService);
		LoginRequest request = new LoginRequest("admin@example.com", "Passw@rd123");
		when(loginService.login(request)).thenReturn(new LoginResult(
			new LoginResponse(1L, UserRole.admin, true),
			"access-token",
			"refresh-token",
			"csrf-token"
		));

		AdminLoginResult result = service.login(request);

		assertThat(result.response().userId()).isEqualTo(1L);
		assertThat(result.response().role()).isEqualTo(UserRole.admin);
		assertThat(result.response().passwordResetRequired()).isTrue();
		assertThat(result.accessToken()).isEqualTo("access-token");
		assertThat(result.refreshToken()).isEqualTo("refresh-token");
		assertThat(result.csrfToken()).isEqualTo("csrf-token");
		verify(loginService).login(request);
	}

	@Test
	void loginThrowsInvalidCredentialsWhenAuthenticatedUserIsNotAdmin() {
		LoginService loginService = mock(LoginService.class);
		AdminLoginService service = new AdminLoginService(loginService);
		LoginRequest request = new LoginRequest("user@example.com", "Passw@rd123");
		when(loginService.login(request)).thenReturn(new LoginResult(
			new LoginResponse(42L, UserRole.user, false),
			"access-token",
			"refresh-token",
			"csrf-token"
		));

		assertThatThrownBy(() -> service.login(request))
			.isInstanceOf(InvalidCredentialsException.class);
	}

	@Test
	void loginPropagatesLoginServiceExceptions() {
		LoginService loginService = mock(LoginService.class);
		AdminLoginService service = new AdminLoginService(loginService);
		LoginRequest request = new LoginRequest("admin@example.com", "Passw@rd123");
		doThrow(new EmailNotVerifiedException()).when(loginService).login(request);

		assertThatThrownBy(() -> service.login(request))
			.isInstanceOf(EmailNotVerifiedException.class);
	}
}
