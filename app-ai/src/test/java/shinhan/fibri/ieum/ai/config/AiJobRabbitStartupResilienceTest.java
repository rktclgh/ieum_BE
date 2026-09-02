package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import shinhan.fibri.ieum.ai.support.AiDatabaseIntegrationTestBase;
import shinhan.fibri.ieum.ai.support.NoNetworkProviderTestConfiguration;

/**
 * app-ai 는 RabbitMQ 브로커가 죽어 있어도(또는 아예 없어도) 기동에 실패하지 않고 정상 서비스해야
 * 한다 — spec.md §9 "브로커 다운" 행("app-ai/app-main 컨슈머는 Spring AMQP 자동 재연결. 유실 없음"),
 * §13.4 health endpoint 결정("브로커가 죽어도 app-main은 정상 동작한다"는 것과 같은 원칙이 app-ai
 * 컨슈머에도 적용된다).
 *
 * <p>{@code AiApplicationTests}는 이 계약을 <b>우연히만</b> 만족한다 — {@code spring.rabbitmq.host}를
 * 오버라이드하지 않으므로 로컬에 우연히 브로커가 없을 때만 이 시나리오를 통과할 뿐, 의도적으로
 * 검증하지 않는다. 이 테스트는 {@code spring.rabbitmq.host}/{@code port}를 도달 불가능한 값으로
 * 명시적으로 못박아 그 계약을 고정한다.
 *
 * <p>연결 타임아웃을 짧게 두어(300ms) 테스트를 빠르게 유지한다 — 127.0.0.1 의 특권 포트(1번)는
 * 로컬에서 즉시 ECONNREFUSED 를 주는 게 보통이지만, 환경에 따라 달라질 수 있어 타임아웃도
 * 별도로 못박는다.
 */
@SpringBootTest
@Import(NoNetworkProviderTestConfiguration.class)
@TestPropertySource(properties = {
	"spring.rabbitmq.host=127.0.0.1",
	"spring.rabbitmq.port=1",
	"spring.rabbitmq.connection-timeout=300ms"
})
class AiJobRabbitStartupResilienceTest extends AiDatabaseIntegrationTestBase {

	@Autowired
	private ConfigurableApplicationContext applicationContext;

	@Autowired
	private RabbitListenerEndpointRegistry rabbitListenerEndpointRegistry;

	@Autowired
	private HealthEndpoint healthEndpoint;

	@Autowired
	private HealthContributorRegistry healthContributorRegistry;

	@Test
	void applicationContextStartsWithAnUnreachableBroker() {
		assertThat(applicationContext.isActive()).isTrue();
	}

	@Test
	void bothQuestionAnswerConsumerContainersAreRegisteredWithoutThrowingDespiteTheUnreachableBroker() {
		Collection<MessageListenerContainer> containers = rabbitListenerEndpointRegistry.getListenerContainers();

		assertThat(containers).hasSize(2);
		// isRunning() 이 true(연결 재시도 중)든 false(아직 시작 못함)든 상관없다 — 핵심은 브로커가
		// 없다는 이유로 컨테이너 상태 조회 자체가 예외를 던지며 죽지 않는다는 것이다.
		assertThatCode(() -> containers.forEach(MessageListenerContainer::isRunning))
			.doesNotThrowAnyException();
	}

	@Test
	void healthEndpointReportsUpWithoutARabbitComponent() {
		assertThat(healthEndpoint.health().getStatus()).isEqualTo(Status.UP);
		// management.health.rabbit.enabled=false (spec.md §13.4) — 브로커 장애가 배포 health gate 를
		// 깨지 않는다는 결정이 실제로 반영되어 rabbit 컨트리뷰터 자체가 등록되지 않았어야 한다.
		assertThat(healthContributorRegistry.getContributor("rabbit")).isNull();
	}
}
