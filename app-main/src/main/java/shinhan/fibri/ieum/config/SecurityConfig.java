package shinhan.fibri.ieum.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import shinhan.fibri.ieum.main.auth.session.CsrfDoubleSubmitFilter;
import shinhan.fibri.ieum.main.auth.session.JwtAuthenticationFilter;

@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;
	private final CsrfDoubleSubmitFilter csrfDoubleSubmitFilter;
	private final JsonAuthenticationEntryPoint jsonAuthenticationEntryPoint;

	@Bean
	SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.formLogin(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.exceptionHandling(exception -> exception.authenticationEntryPoint(jsonAuthenticationEntryPoint))
			.authorizeHttpRequests(authorize -> authorize
				.requestMatchers(
					"/api/v1/auth/**",
					"/swagger-ui/**",
					"/v3/api-docs/**",
					"/actuator/health"
				).permitAll()
				.anyRequest().authenticated()
			)
			.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
			.addFilterAfter(csrfDoubleSubmitFilter, JwtAuthenticationFilter.class)
			.build();
	}
}
