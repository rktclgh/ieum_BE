package shinhan.fibri.ieum.main.auth.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.SendEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.service.EmailVerificationService;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AuthControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private EmailVerificationService emailVerificationService;

	@Test
	void sendEmailVerificationCodeReturnsExpirySeconds() throws Exception {
		when(emailVerificationService.sendSignupCode(any(SendEmailVerificationRequest.class)))
			.thenReturn(new SendEmailVerificationResponse(180));

		mockMvc.perform(post("/api/v1/auth/email/send")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.expiresInSeconds", is(180)));
	}

	@Test
	void verifyEmailVerificationCodeReturnsVerificationToken() throws Exception {
		when(emailVerificationService.verifySignupCode(any(VerifyEmailVerificationRequest.class)))
			.thenReturn(new VerifyEmailVerificationResponse("verification-token", 1800));

		mockMvc.perform(post("/api/v1/auth/email/verify")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "code": "123456"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.emailVerificationToken", is("verification-token")))
			.andExpect(jsonPath("$.expiresInSeconds", is(1800)));
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		EmailVerificationService emailVerificationService() {
			return mock(EmailVerificationService.class);
		}
	}
}
