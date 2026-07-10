package shinhan.fibri.ieum.main.admin.controller;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import shinhan.fibri.ieum.main.admin.dto.AdminLoginResponse;
import shinhan.fibri.ieum.main.admin.service.AdminLoginResult;
import shinhan.fibri.ieum.main.admin.service.AdminLoginService;
import shinhan.fibri.ieum.main.auth.dto.LoginRequest;
import shinhan.fibri.ieum.main.auth.session.AuthCookieWriter;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class AdminAuthController {

	private final AdminLoginService adminLoginService;
	private final AuthCookieWriter authCookieWriter;

	@PostMapping("/login")
	public ResponseEntity<AdminLoginResponse> login(
		@Valid @RequestBody LoginRequest request,
		HttpServletResponse response
	) {
		AdminLoginResult result = adminLoginService.login(request);
		authCookieWriter.writeLoginCookies(
			response,
			result.accessToken(),
			result.refreshToken(),
			result.csrfToken()
		);
		return ResponseEntity.ok(result.response());
	}
}
