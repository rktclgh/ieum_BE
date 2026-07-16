# 비동기 재디스패치 JWT 인증 복구 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 보호된 비동기 재디스패치에서도 JWT 인증을 복원해 응답 커밋 뒤의 `AuthorizationDeniedException`을 제거한다.

**Architecture:** `OncePerRequestFilter`의 ASYNC 기본 skip만 명시적으로 해제한다. 기존 쿠키 추출과 세션 검증 로직을 ASYNC 요청에도 재사용하며, SecurityConfig의 인가 규칙은 바꾸지 않는다.

**Tech Stack:** Java 21, Spring Security, Spring MVC Test, Mockito, Gradle.

## Global Constraints

- `/api/**.authenticated()` 인가 규칙을 완화하거나 `DispatcherType.ASYNC`를 permitAll로 추가하지 않는다.
- ERROR 디스패치, CSRF, 컨트롤러 응답 형식은 변경하지 않는다.
- 유효한 access-token 쿠키가 없는 ASYNC 요청을 인증하지 않는다.
- 테스트를 먼저 작성하고 수정 전 실패를 확인한다.

---

### Task 1: ASYNC 디스패치 JWT 재인증

**Files:**
- Modify: `app-main/src/test/java/shinhan/fibri/ieum/main/auth/session/JwtAuthenticationFilterTest.java`
- Modify: `app-main/src/main/java/shinhan/fibri/ieum/main/auth/session/JwtAuthenticationFilter.java`
- Create: `docs/superpowers/specs/2026-07-16-async-jwt-redispatch-design.md`
- Create: `docs/superpowers/plans/2026-07-16-async-jwt-redispatch.md`

**Interfaces:**
- Consumes: `SessionTokenValidator.validateSession(String)` and the `access_token` cookie.
- Produces: an `AuthenticatedUser` plus `AuthenticatedSessionDetails` in `SecurityContextHolder` during an ASYNC dispatcher request.

- [ ] **Step 1: Write the failing ASYNC regression test**

Add `import jakarta.servlet.DispatcherType;` and this test to `JwtAuthenticationFilterTest`:

```java
@Test
void doFilterAuthenticatesBackendAsyncRedispatchWithValidAccessCookie() throws Exception {
	SessionTokenValidator validator = mock(SessionTokenValidator.class);
	JwtAuthenticationFilter filter = new JwtAuthenticationFilter(validator);
	AuthenticatedUser principal = new AuthenticatedUser(
		42L,
		"user@example.com",
		UserRole.user,
		UserStatus.active
	);
	when(validator.validateSession("access-token"))
		.thenReturn(Optional.of(new ValidatedAuthSession(principal, "session-42")));
	MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/files/1");
	request.setDispatcherType(DispatcherType.ASYNC);
	request.setCookies(new MockCookie("access_token", "access-token"));
	MockHttpServletResponse response = new MockHttpServletResponse();
	FilterChain chain = mock(FilterChain.class);

	filter.doFilter(request, response, chain);

	assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
	assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(principal);
	assertThat(SecurityContextHolder.getContext().getAuthentication().getDetails())
		.isEqualTo(new AuthenticatedSessionDetails("session-42"));
	verify(validator).validateSession("access-token");
	verify(chain).doFilter(request, response);
}
```

- [ ] **Step 2: Run the test to verify it fails before the fix**

Run:

```bash
./gradlew :app-main:test --tests shinhan.fibri.ieum.main.auth.session.JwtAuthenticationFilterTest.doFilterAuthenticatesBackendAsyncRedispatchWithValidAccessCookie
```

Expected: FAIL because the default `OncePerRequestFilter` ASYNC policy skips `doFilterInternal`, leaving the SecurityContext unauthenticated.

- [ ] **Step 3: Re-enable the existing filter for ASYNC dispatches**

Add this method immediately after `shouldNotFilter` in `JwtAuthenticationFilter`:

```java
@Override
protected boolean shouldNotFilterAsyncDispatch() {
	return false;
}
```

Do not change `shouldNotFilter`, `doFilterInternal`, or `SecurityConfig`.

- [ ] **Step 4: Run the focused regression suite**

Run:

```bash
./gradlew :app-main:test --tests shinhan.fibri.ieum.main.auth.session.JwtAuthenticationFilterTest --tests shinhan.fibri.ieum.main.file.controller.FileControllerTest
```

Expected: PASS. The first class proves authentication is rebuilt on ASYNC dispatch; the second preserves the existing streaming response behavior.

- [ ] **Step 5: Commit the scoped change**

```bash
git add docs/superpowers/specs/2026-07-16-async-jwt-redispatch-design.md docs/superpowers/plans/2026-07-16-async-jwt-redispatch.md app-main/src/main/java/shinhan/fibri/ieum/main/auth/session/JwtAuthenticationFilter.java app-main/src/test/java/shinhan/fibri/ieum/main/auth/session/JwtAuthenticationFilterTest.java
git commit -m "fix: authenticate async redispatches"
```
