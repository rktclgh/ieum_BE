package shinhan.fibri.ieum.main.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthCookieWriterTest {

	@Test
	void writeLoginCookiesAddsAccessRefreshAndCsrfCookies() {
		AuthCookieWriter writer = new AuthCookieWriter(new AuthSessionProperties(
			true,
			"Lax",
			"",
			1800,
			1209600
		));
		MockHttpServletResponse response = new MockHttpServletResponse();

		writer.writeLoginCookies(response, "access-token", "refresh-token", "csrf-token");

		assertThat(response.getHeaders("Set-Cookie"))
			.anySatisfy(cookie -> assertThat(cookie)
				.contains("access_token=access-token")
				.contains("Path=/")
				.contains("Max-Age=1800")
				.contains("Secure")
				.contains("HttpOnly")
				.contains("SameSite=Lax"))
			.anySatisfy(cookie -> assertThat(cookie)
				.contains("refresh_token=refresh-token")
				.contains("Path=/api/v1/auth")
				.contains("Max-Age=1209600")
				.contains("Secure")
				.contains("HttpOnly")
				.contains("SameSite=Lax"))
			.anySatisfy(cookie -> assertThat(cookie)
				.contains("csrf_token=csrf-token")
				.contains("Path=/")
				.contains("Max-Age=1209600")
				.contains("Secure")
				.contains("SameSite=Lax")
				.doesNotContain("HttpOnly"));
	}

	@Test
	void writeExpiredAuthCookiesExpiresAccessRefreshAndCsrfCookies() {
		AuthCookieWriter writer = new AuthCookieWriter(new AuthSessionProperties(
			true,
			"Lax",
			"",
			1800,
			1209600
		));
		MockHttpServletResponse response = new MockHttpServletResponse();

		writer.writeExpiredAuthCookies(response);

		assertThat(response.getHeaders("Set-Cookie"))
			.anySatisfy(cookie -> assertThat(cookie)
				.contains("access_token=")
				.contains("Path=/")
				.contains("Max-Age=0")
				.contains("Secure")
				.contains("HttpOnly")
				.contains("SameSite=Lax"))
			.anySatisfy(cookie -> assertThat(cookie)
				.contains("refresh_token=")
				.contains("Path=/api/v1/auth")
				.contains("Max-Age=0")
				.contains("Secure")
				.contains("HttpOnly")
				.contains("SameSite=Lax"))
			.anySatisfy(cookie -> assertThat(cookie)
				.contains("csrf_token=")
				.contains("Path=/")
				.contains("Max-Age=0")
				.contains("Secure")
				.contains("SameSite=Lax")
				.doesNotContain("HttpOnly"));
	}
}
