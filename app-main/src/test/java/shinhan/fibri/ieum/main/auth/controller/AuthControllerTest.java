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
import shinhan.fibri.ieum.main.auth.dto.SignupRequest;
import shinhan.fibri.ieum.main.auth.dto.SignupResponse;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationRequest;
import shinhan.fibri.ieum.main.auth.dto.VerifyEmailVerificationResponse;
import shinhan.fibri.ieum.main.auth.exception.EmailTakenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationCodeException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationTokenException;
import shinhan.fibri.ieum.main.auth.exception.NicknameTakenException;
import shinhan.fibri.ieum.main.auth.service.EmailVerificationService;
import shinhan.fibri.ieum.main.auth.service.SignupService;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
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

	@Autowired
	private SignupService signupService;

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

	@Test
	void verifyEmailVerificationCodeReturnsBadRequestWhenCodeIsInvalid() throws Exception {
		doThrow(new InvalidEmailVerificationCodeException())
			.when(emailVerificationService)
			.verifySignupCode(any(VerifyEmailVerificationRequest.class));

		mockMvc.perform(post("/api/v1/auth/email/verify")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "code": "000000"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_EMAIL_VERIFICATION_CODE")))
			.andExpect(jsonPath("$.message", is("Invalid email verification code")));
	}

	@Test
	void signupReturnsCreatedUserId() throws Exception {
		when(signupService.signup(any(SignupRequest.class)))
			.thenReturn(new SignupResponse(42L));

		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId", is(42)));
	}

	@Test
	void signupReturnsBadRequestWhenVerificationTokenIsInvalid() throws Exception {
		doThrow(new InvalidEmailVerificationTokenException())
			.when(signupService)
			.signup(any(SignupRequest.class));

		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "emailVerificationToken": "invalid-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_EMAIL_VERIFICATION_TOKEN")))
			.andExpect(jsonPath("$.message", is("Invalid email verification token")));
	}

	@Test
	void signupReturnsConflictWhenEmailIsTaken() throws Exception {
		doThrow(new EmailTakenException())
			.when(signupService)
			.signup(any(SignupRequest.class));

		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("EMAIL_TAKEN")))
			.andExpect(jsonPath("$.message", is("Email is already taken")));
	}

	@Test
	void signupReturnsConflictWhenNicknameIsTaken() throws Exception {
		doThrow(new NicknameTakenException())
			.when(signupService)
			.signup(any(SignupRequest.class));

		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code", is("NICKNAME_TAKEN")))
			.andExpect(jsonPath("$.message", is("Nickname is already taken")));
	}

	@Test
	void signupReturnsBadRequestWhenPasswordIsTooShort() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Pass@1",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void signupReturnsBadRequestWhenPasswordHasNoSpecialCharacter() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Password123",
					  "nickname": "nickname",
					  "birthDate": "2000-01-01",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void signupReturnsBadRequestWhenNicknameIsShorterThanTwoCharacters() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "n",
					  "birthDate": "2000-01-01",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@Test
	void signupReturnsBadRequestWhenNicknameContainsProfanity() throws Exception {
		mockMvc.perform(post("/api/v1/auth/signup")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "email": "USER@example.com",
					  "password": "Passw@rd123",
					  "nickname": "fuckmaster",
					  "birthDate": "2000-01-01",
					  "emailVerificationToken": "verification-token"
					}
					"""))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")));
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		EmailVerificationService emailVerificationService() {
			return mock(EmailVerificationService.class);
		}

		@Bean
		@Primary
		SignupService signupService() {
			return mock(SignupService.class);
		}
	}
}
