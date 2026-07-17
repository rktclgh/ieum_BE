package shinhan.fibri.ieum.config;

import jakarta.servlet.DispatcherType;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import shinhan.fibri.ieum.main.auth.session.CsrfDoubleSubmitFilter;
import shinhan.fibri.ieum.main.auth.session.JwtAuthenticationFilter;
import shinhan.fibri.ieum.main.notification.internal.InternalAiCallbackEndpoint;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final CsrfDoubleSubmitFilter csrfDoubleSubmitFilter;
	private final JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint;
	private final JsonAccessDeniedHandler jsonAccessDeniedHandler;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.cors(Customizer.withDefaults())
			.csrf(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.exceptionHandling(exception -> exception
				.authenticationEntryPoint(jsonAuthenticationEntryPoint)
				.accessDeniedHandler(jsonAccessDeniedHandler)
			)
			.authorizeHttpRequests(authorize -> authorize
				.dispatcherTypeMatchers(DispatcherType.ASYNC).permitAll()
				.requestMatchers(HttpMethod.GET, "/api/places/**").permitAll()
				.requestMatchers(HttpMethod.POST, InternalAiCallbackEndpoint.SECURITY_PATTERN).permitAll()
				.requestMatchers(
					"/api/v1/auth/**",
					"/ws/**",
					"/swagger-ui",
					"/swagger-ui.html",
					"/swagger-ui/**",
					"/v3/api-docs",
					"/v3/api-docs.yaml",
					"/v3/api-docs/**",
					"/actuator/health"
				).permitAll()
				.requestMatchers(HttpMethod.POST, "/api/v1/inquiries/suspended-users").permitAll()
				.requestMatchers("/api/v1/admin/**").hasRole("admin")
				.requestMatchers("/api/**").authenticated()
				.requestMatchers("/actuator/**").authenticated()
				.requestMatchers(HttpMethod.GET, "/**").permitAll()
				.requestMatchers(HttpMethod.HEAD, "/**").permitAll()
				.anyRequest().authenticated()
			)
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterAfter(csrfDoubleSubmitFilter, AuthorizationFilter.class)
			.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(
		@Value("${app.cors.allowed-origins}") String allowedOrigins
	) {
		CorsConfiguration configuration = new CorsConfiguration();
		configuration.setAllowedOrigins(csvValues(allowedOrigins));
		configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
		configuration.setAllowedHeaders(List.of("*"));
		configuration.setAllowCredentials(true);
		configuration.setMaxAge(3600L);

		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/**", configuration);
		return source;
	}

	private List<String> csvValues(String value) {
		if (value == null) {
			throw new IllegalStateException(
				"app.cors.allowed-origins must be a non-empty list of explicit origins when credentials are allowed"
			);
		}

		List<String> origins = Arrays.stream(value.split(","))
			.map(String::trim)
			.filter(origin -> !origin.isBlank())
			.toList();
		if (origins.isEmpty() || origins.contains("*")) {
			throw new IllegalStateException(
				"app.cors.allowed-origins must be a non-empty list of explicit origins when credentials are allowed"
			);
		}
		return origins;
	}
}
