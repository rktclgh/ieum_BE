package shinhan.fibri.ieum.main.meeting.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingResponse;
import shinhan.fibri.ieum.main.meeting.exception.InvalidMeetingRequestException;
import shinhan.fibri.ieum.main.meeting.service.MeetingService;

@WebMvcTest(MeetingController.class)
@AutoConfigureMockMvc(addFilters = false)
class MeetingControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private MeetingService meetingService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(meetingService);
	}

	@Test
	void createReturnsCreatedMeetingIds() throws Exception {
		when(meetingService.create(any(AuthenticatedUser.class), any()))
			.thenReturn(new CreateMeetingResponse(3L, 11L, 9L));

		mockMvc.perform(post("/api/v1/meetings")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "저녁 모임",
					  "content": "같이 밥 먹어요",
					  "placeName": "동선역 2번 출구",
					  "meetingAt": "2026-07-10T19:00:00+09:00",
					  "maxMembers": 7,
					  "lat": 37.5,
					  "lng": 127.0,
					  "imageFileId": "00000000-0000-0000-0000-000000000001"
					}
					""")
				.with(authenticated()))
			.andExpect(status().isCreated())
			.andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/meetings/3"))
			.andExpect(jsonPath("$.meetingId", is(3)))
			.andExpect(jsonPath("$.pinId", is(11)))
			.andExpect(jsonPath("$.roomId", is(9)));

		verify(meetingService).create(any(AuthenticatedUser.class), any());
	}

	@Test
	void createValidatesRequiredFields() throws Exception {
		mockMvc.perform(post("/api/v1/meetings")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{}")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("lat")));
	}

	@Test
	void mapsInvalidMeetingRequestToValidationFailed() throws Exception {
		when(meetingService.create(any(AuthenticatedUser.class), any()))
			.thenThrow(new InvalidMeetingRequestException("VALIDATION_FAILED", "imageFileId", "Invalid image"));

		mockMvc.perform(post("/api/v1/meetings")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{
					  "title": "저녁 모임",
					  "placeName": "동선역 2번 출구",
					  "meetingAt": "2026-07-10T19:00:00+09:00",
					  "maxMembers": 7,
					  "lat": 37.5,
					  "lng": 127.0,
					  "imageFileId": "00000000-0000-0000-0000-000000000001"
					}
					""")
				.with(authenticated()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("imageFileId")));
	}

	private static RequestPostProcessor authenticated() {
		return request -> {
			AuthenticatedUser principal = new AuthenticatedUser(
				42L,
				"user@example.com",
				UserRole.user,
				UserStatus.active
			);
			SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken(principal, null));
			return request;
		};
	}

	@TestConfiguration
	static class TestConfig implements WebMvcConfigurer {

		@Override
		public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) {
			resolvers.add(new AuthenticationPrincipalArgumentResolver());
		}

		@Bean
		@Primary
		MeetingService meetingService() {
			return mock(MeetingService.class);
		}

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}
	}
}
