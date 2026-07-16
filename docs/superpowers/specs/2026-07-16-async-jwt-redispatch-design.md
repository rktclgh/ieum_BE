# 비동기 재디스패치 JWT 인증 복구 설계

## 문제

인증된 사용자가 비동기 응답을 소비할 때 `AuthorizationDeniedException`이 응답 커밋 뒤에 발생한다. 제공된 로그의 호출 순서는 `AsyncContextImpl` → `ApplicationDispatcher.dispatch` → `AnonymousAuthenticationFilter` → `AuthorizationFilter`이며, `/api/**`가 인증을 요구하는 현재 보안 정책과 충돌한다.

파일 스트리밍 엔드포인트 `GET /api/v1/files/{fileId}`는 `StreamingResponseBody`를 반환하므로 첫 번째 재현 후보이다. SSE 구독도 비동기 응답이지만, 로그에 URI가 없어 파일 스트리밍만의 문제로 단정하지 않는다.

## 근본 원인

`JwtAuthenticationFilter`는 `OncePerRequestFilter`를 상속하지만 ASYNC 디스패치를 재처리하도록 명시하지 않았다. Spring의 기본값은 ASYNC 디스패치를 건너뛴다. 따라서 최초 요청에서 쿠키 기반 세션 검증으로 세운 인증 정보가 비동기 재디스패치에서 다시 세워지지 않고, 보호된 `/api/**` 요청이 익명 사용자로 인가 단계에 도달한다.

## 결정

`JwtAuthenticationFilter.shouldNotFilterAsyncDispatch()`를 `false`로 오버라이드한다. ASYNC 재디스패치에서도 기존 access-token 쿠키와 `SessionTokenValidator`를 사용해 같은 `SecurityContext`를 다시 만든다.

이 방식은 보안 설정을 완화하지 않는다. 특히 `DispatcherType.ASYNC`를 전역 `permitAll`로 두지 않으며, `/api/**.authenticated()`와 CSRF 범위는 바꾸지 않는다.

## 범위

- `JwtAuthenticationFilter`의 ASYNC 디스패치 동작만 변경한다.
- 유효한 access-token 쿠키가 있는 보호된 비동기 재디스패치에서 validator가 다시 호출되고 인증이 복원되는 회귀 테스트를 추가한다.
- 기존 일반 요청, 내부 AI 콜백 제외, 안전한 프런트엔드 정적 read 제외 규칙은 유지한다.

## 비범위

- SecurityConfig의 인가 규칙 변경
- SSE 또는 파일 컨트롤러의 응답 형식 변경
- ERROR 디스패치 처리 정책 변경
- 로그에 URI가 없다는 이유로 특정 컨트롤러를 임의 수정하는 작업

## 불변 조건

1. ASYNC 재디스패치도 유효한 access-token 없이는 인증되지 않는다.
2. ASYNC 재디스패치에서 access-token이 유효하면 `AuthenticatedUser`와 `AuthenticatedSessionDetails`가 기존 요청과 동일하게 설정된다.
3. 보호 URL의 인가 요구사항은 그대로 유지된다.

## 검증

- 단위 테스트는 `MockHttpServletRequest`의 dispatcher type을 `ASYNC`로 설정하고, 수정 전에는 validator가 호출되지 않아 실패해야 한다.
- 수정 후 validator 호출과 SecurityContext의 principal/details를 검증한다.
- 대상 클래스와 기존 파일 스트리밍 컨트롤러 테스트를 Gradle로 함께 실행한다.
