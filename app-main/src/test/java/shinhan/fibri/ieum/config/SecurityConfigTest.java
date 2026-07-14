package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import shinhan.fibri.ieum.main.auth.session.CsrfDoubleSubmitFilter;
import shinhan.fibri.ieum.main.auth.session.JwtAuthenticationFilter;

class SecurityConfigTest {

	private final SecurityConfig securityConfig = new SecurityConfig(
		mock(JwtAuthenticationFilter.class),
		mock(CsrfDoubleSubmitFilter.class),
		mock(JsonAuthenticationEntryPoint.class),
		mock(JsonAccessDeniedHandler.class)
	);

	@Test
	void corsConfigurationAllowsAllRequestedHeaders() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRequestURI("/api/v1/auth/login");

		CorsConfiguration configuration = securityConfig
			.corsConfigurationSource("http://localhost:3000")
			.getCorsConfiguration(request);

		assertThat(configuration).isNotNull();
		assertThat(configuration.getAllowedHeaders()).isEqualTo(List.of("*"));
	}

	@Test
	void corsConfigurationRejectsBlankAllowedOrigins() {
		assertThatThrownBy(() -> securityConfig.corsConfigurationSource("  , "))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.cors.allowed-origins");
	}

	@Test
	void corsConfigurationRejectsWildcardAllowedOriginsWhenCredentialsAreAllowed() {
		assertThatThrownBy(() -> securityConfig.corsConfigurationSource("*"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("explicit origins");
	}

	@Test
	void corsConfigurationRejectsNullAllowedOrigins() {
		assertThatThrownBy(() -> securityConfig.corsConfigurationSource(null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("app.cors.allowed-origins");
	}
}
