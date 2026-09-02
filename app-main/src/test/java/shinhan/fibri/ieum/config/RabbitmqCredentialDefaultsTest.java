package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.StandardEnvironment;

/**
 * app-main의 {@code spring.rabbitmq.username}/{@code password} 기본값 회귀 검증(finding I1).
 *
 * <p>이전 값은 {@code ${RABBITMQ_USERNAME:}} — 빈 문자열 기본값이었다. Spring Boot의 RabbitMQ
 * 자동설정 자체 기본값은 {@code guest}이므로, "환경변수 미설정"과 "빈 문자열로 명시 설정"을 구분하지
 * 못해 로컬 브로커(guest/guest)에서도 {@code ACCESS_REFUSED}가 났다. app-ai는 이미
 * {@code guest}/{@code guest}를 기본값으로 쓴다(app-ai/src/main/resources/application.properties) —
 * app-main도 같은 기본값으로 맞춘다.
 */
class RabbitmqCredentialDefaultsTest {

	@Test
	void defaultsToGuestWhenEnvironmentVariablesAreUnset() throws IOException {
		StandardEnvironment environment = environmentWithout("RABBITMQ_USERNAME", "RABBITMQ_PASSWORD");

		assertThat(environment.getProperty("spring.rabbitmq.username")).isEqualTo("guest");
		assertThat(environment.getProperty("spring.rabbitmq.password")).isEqualTo("guest");
	}

	@Test
	void honorsExplicitEnvironmentVariablesOverTheGuestDefault() throws IOException {
		StandardEnvironment environment = environmentWithout("RABBITMQ_USERNAME", "RABBITMQ_PASSWORD");
		environment.getPropertySources()
			.addFirst(new MapPropertySource(
				"explicit", Map.of("RABBITMQ_USERNAME", "ieum_main", "RABBITMQ_PASSWORD", "secret")
			));

		assertThat(environment.getProperty("spring.rabbitmq.username")).isEqualTo("ieum_main");
		assertThat(environment.getProperty("spring.rabbitmq.password")).isEqualTo("secret");
	}

	/**
	 * {@code RABBITMQ_USERNAME}/{@code RABBITMQ_PASSWORD}가 실제로 설정된 실행 환경(CI, 개발자 셸)에서
	 * 테스트가 그 값을 우연히 주워 통과하지 않도록, 시스템 환경변수/프로퍼티 소스를 제거하고 순수
	 * application.properties 소스만 남긴다.
	 */
	private StandardEnvironment environmentWithout(String... keys) throws IOException {
		StandardEnvironment environment = new StandardEnvironment();
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
		environment.getPropertySources().addLast(new PropertiesPropertySource("application", applicationProperties()));
		return environment;
	}

	/**
	 * {@code ClassPathResource("application.properties")}는 쓸 수 없다 —
	 * {@code src/test/resources/application.properties}(H2/테스트 픽스처용, 별개 파일)가 테스트
	 * 클래스패스에서 main 리소스보다 먼저 온다. 검증 대상은 실제 배포에 쓰이는 main 리소스이므로
	 * 파일 경로로 직접 읽는다.
	 */
	private Properties applicationProperties() throws IOException {
		Properties properties = new Properties();
		try (var input = Files.newInputStream(Path.of("src/main/resources/application.properties"))) {
			properties.load(input);
		}
		return properties;
	}
}
