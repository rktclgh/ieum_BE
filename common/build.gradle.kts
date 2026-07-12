plugins {
	`java-library`
	`java-test-fixtures`
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

// 공용 라이브러리 모듈: 실행 가능한 bootJar 대신 일반 jar 만 만든다.
tasks.bootJar { enabled = false }
tasks.jar { enabled = true }

dependencies {
	// api 로 노출해야 app-main / app-ai 가 전이 의존으로 함께 받는다.
	api("org.springframework.boot:spring-boot-starter-data-jpa")
	api("org.springframework.boot:spring-boot-starter-validation")
	api("com.fasterxml.jackson.core:jackson-databind")

	compileOnly("org.projectlombok:lombok")
	annotationProcessor("org.projectlombok:lombok")

	testImplementation("org.springframework.boot:spring-boot-starter-data-jpa-test")
	testFixturesImplementation("org.springframework.boot:spring-boot-starter-jdbc")
	testFixturesImplementation("org.testcontainers:testcontainers-postgresql:2.0.5")
	testFixturesImplementation("org.postgresql:postgresql")
	runtimeOnly("org.postgresql:postgresql")
	testRuntimeOnly("com.h2database:h2")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testCompileOnly("org.projectlombok:lombok")
	testAnnotationProcessor("org.projectlombok:lombok")
}

sourceSets {
	testFixtures {
		resources.srcDir(rootProject.file("../db"))
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
