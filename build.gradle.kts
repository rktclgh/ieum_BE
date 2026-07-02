// 루트는 애그리게이터 역할만 한다. 실제 코드/의존성은 각 하위 모듈에 있다.
//   common    : 엔티티·Repository·DTO 등 공용 코드 (라이브러리, 실행 불가)
//   app-main  : EC2-1 배포용 — 핵심 REST API + WebSocket + SSE
//   app-ai    : EC2-2 배포용 — AI 전용
plugins {
	id("org.springframework.boot") version "4.0.7" apply false
	id("io.spring.dependency-management") version "1.1.7" apply false
}

allprojects {
	group = "shinhan.fibri"
	version = "0.0.1-SNAPSHOT"

	repositories {
		mavenCentral()
	}
}
