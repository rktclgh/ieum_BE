package shinhan.fibri.ieum.main.admin.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.main.admin.dto.AdminLoginResponse;
import shinhan.fibri.ieum.main.auth.dto.LoginRequest;
import shinhan.fibri.ieum.main.auth.dto.LoginResponse;
import shinhan.fibri.ieum.main.auth.exception.InvalidCredentialsException;
import shinhan.fibri.ieum.main.auth.service.LoginResult;
import shinhan.fibri.ieum.main.auth.service.LoginService;

@Service
@RequiredArgsConstructor
public class AdminLoginService {

	private final LoginService loginService;

	@Transactional
	public AdminLoginResult login(LoginRequest request) {
		LoginResult result = loginService.login(request);
		LoginResponse response = result.response();
		if (response.role() != UserRole.admin) {
			throw new InvalidCredentialsException();
		}

		return new AdminLoginResult(
			new AdminLoginResponse(response.userId(), response.role(), response.passwordResetRequired()),
			result.accessToken(),
			result.refreshToken(),
			result.csrfToken()
		);
	}
}
