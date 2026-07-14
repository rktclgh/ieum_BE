package shinhan.fibri.ieum.main.admin.user.controller;

import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.UserGrade;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.admin.user.domain.SanctionType;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserActivity;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserDetailResponse;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserItem;
import shinhan.fibri.ieum.main.admin.user.dto.AdminUserProfile;
import shinhan.fibri.ieum.main.admin.user.dto.CreateSanctionResponse;
import shinhan.fibri.ieum.main.admin.user.dto.CursorPage;
import shinhan.fibri.ieum.main.admin.user.exception.InvalidAdminCursorException;
import shinhan.fibri.ieum.main.admin.user.service.AdminSanctionService;
import shinhan.fibri.ieum.main.admin.user.service.AdminUserRoleService;
import shinhan.fibri.ieum.main.admin.user.service.AdminUserQueryService;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@WebMvcTest(AdminUserController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminUserControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private AdminUserQueryService queryService;

	@Autowired
	private AdminSanctionService sanctionService;

	@Autowired
	private AdminUserRoleService roleService;

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
		reset(queryService, sanctionService, roleService);
	}

	@Test
	void getUsersReturnsCursorPage() throws Exception {
		when(queryService.getUsers(any()))
			.thenReturn(new CursorPage<>(List.of(userItem()), "next"));

		mockMvc.perform(get("/api/v1/admin/users")
				.with(admin())
				.param("status", "active")
				.param("size", "20"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.nextCursor", is("next")))
			.andExpect(jsonPath("$.items[0].userId", is(10)))
			.andExpect(jsonPath("$.items[0].role", is("user")))
			.andExpect(jsonPath("$.items[0].status", is("active")));
	}

	@Test
	void getUserReturnsDetail() throws Exception {
		when(queryService.getUser(10L)).thenReturn(detail());

		mockMvc.perform(get("/api/v1/admin/users/10").with(admin()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.user.userId", is(10)))
			.andExpect(jsonPath("$.activity.questionCount", is(2)))
			.andExpect(jsonPath("$.reports").isArray())
			.andExpect(jsonPath("$.sanctions").isArray());
	}

	@Test
	void sanctionReturnsCreatedResponse() throws Exception {
		when(sanctionService.sanction(any(), any(), any())).thenReturn(new CreateSanctionResponse(99L));

		mockMvc.perform(post("/api/v1/admin/users/10/sanctions")
				.with(admin())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"type":"permanent","reason":"abuse"}
					"""))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.sanctionId", is(99)));
	}

	@Test
	void activateReturnsNoContent() throws Exception {
		mockMvc.perform(post("/api/v1/admin/users/10/activate").with(admin()))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(sanctionService).activate(any(), any());
	}

	@Test
	void changeRoleReturnsNoContent() throws Exception {
		mockMvc.perform(patch("/api/v1/admin/users/10/role")
				.with(admin())
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
					{"role":"user"}
					"""))
			.andExpect(status().isNoContent())
			.andExpect(content().string(""));

		verify(roleService).changeRole(any(AuthenticatedUser.class), eq(10L), eq(UserRole.user));
	}

	@Test
	void invalidCursorMapsToBadRequest() throws Exception {
		when(queryService.getUsers(any())).thenThrow(new InvalidAdminCursorException());

		mockMvc.perform(get("/api/v1/admin/users")
				.with(admin())
				.param("cursor", "bad"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("INVALID_CURSOR")))
			.andExpect(jsonPath("$.fieldErrors[0].field", is("cursor")));
	}

	private static RequestPostProcessor admin() {
		return request -> {
			AuthenticatedUser principal = new AuthenticatedUser(
				1L,
				"admin@example.com",
				UserRole.admin,
				UserStatus.active
			);
			SecurityContextHolder.getContext()
				.setAuthentication(new TestingAuthenticationToken(principal, null));
			return request;
		};
	}

	private static AdminUserItem userItem() {
		return new AdminUserItem(
			10L,
			"user@example.com",
			"user",
			UserRole.user,
			UserStatus.active,
			UserGrade.bronze,
			AuthProvider.email,
			OffsetDateTime.parse("2026-07-10T10:00:00+09:00")
		);
	}

	private static AdminUserDetailResponse detail() {
		return new AdminUserDetailResponse(
			new AdminUserProfile(
				10L,
				"user@example.com",
				"user",
				UserRole.user,
				UserStatus.active,
				UserGrade.bronze,
				AuthProvider.email,
				LocalDate.of(2000, 1, 1),
				GenderType.female,
				"KR",
				null,
				null
			),
			new AdminUserActivity(2, 3, 1, 4),
			List.of(),
			List.of()
		);
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		AdminUserQueryService adminUserQueryService() {
			return mock(AdminUserQueryService.class);
		}

		@Bean
		@Primary
		AdminSanctionService adminSanctionService() {
			return mock(AdminSanctionService.class);
		}

		@Bean
		@Primary
		AdminUserRoleService adminUserRoleService() {
			return mock(AdminUserRoleService.class);
		}

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}

		@Bean
		WebMvcConfigurer authenticationPrincipalArgumentResolverConfigurer() {
			return new WebMvcConfigurer() {
				@Override
				public void addArgumentResolvers(java.util.List<HandlerMethodArgumentResolver> resolvers) {
					resolvers.add(new AuthenticationPrincipalArgumentResolver());
				}
			};
		}
	}
}
