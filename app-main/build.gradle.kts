plugins {
	java
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

// 실행 가능한 bootJar 만 남기고 plain jar 는 끈다 → 도커 COPY 시 jar 하나로 명확해진다.
tasks.jar { enabled = false }
tasks.bootJar { archiveFileName.set("app-main.jar") }

dependencies {
	implementation(project(":common"))

	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-data-redis")
	implementation("org.springframework.boot:spring-boot-starter-mail")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.security:spring-security-crypto")
	implementation("org.springframework.security:spring-security-oauth2-jose")
	implementation(platform("software.amazon.awssdk:bom:2.47.0"))
	implementation("software.amazon.awssdk:s3")
	implementation("com.sksamuel.scrimage:scrimage-core:4.6.5")
	implementation("com.sksamuel.scrimage:scrimage-webp:4.6.5")
	// WebSocket (SSE 자체는 webmvc 만으로 동작)
	implementation("org.springframework.boot:spring-boot-starter-websocket")
	implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.2")

	compileOnly("org.projectlombok:lombok")
	developmentOnly("org.springframework.boot:spring-boot-devtools")
	annotationProcessor("org.projectlombok:lombok")

	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testImplementation(testFixtures(project(":common")))
	testImplementation("org.testcontainers:testcontainers-junit-jupiter:2.0.5")
	testImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
	testRuntimeOnly("com.h2database:h2")
	testRuntimeOnly("org.postgresql:postgresql")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
	useJUnitPlatform()
}
