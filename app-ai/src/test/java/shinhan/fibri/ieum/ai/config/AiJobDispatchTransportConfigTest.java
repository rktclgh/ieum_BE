package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * app-ai 쪽 {@code app.ai.dispatch.transport} 기동 검증(리뷰 발견 사항 I4) — app-main의
 * {@code AiJobDispatchTransportSwitchTest}가 검증하는 것과 같은 계약을, app-ai에도 똑같이 채운다.
 * 오타 값(예: {@code carrier-pigeon})이 조용히 기본값으로 떨어지지 않고 컨텍스트 기동을 막는지, 그리고
 * app-ai의 기본값이 app-main과 달리 {@code rabbitmq}인지 확인한다.
 */
class AiJobDispatchTransportConfigTest {

	private ApplicationContextRunner runner() {
		return new ApplicationContextRunner().withUserConfiguration(AiJobDispatchTransportConfig.class);
	}

	@Test
	void missingTransportDefaultsToRabbitmq() {
		runner().run(context -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(AiJobDispatchTransportConfig.AiJobDispatchTransportValidated.class).value())
				.isEqualTo("rabbitmq");
		});
	}

	@Test
	void explicitHttpIsAccepted() {
		runner()
			.withPropertyValues("app.ai.dispatch.transport=http")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(AiJobDispatchTransportConfig.AiJobDispatchTransportValidated.class).value())
					.isEqualTo("http");
			});
	}

	@Test
	void explicitRabbitmqIsAccepted() {
		runner()
			.withPropertyValues("app.ai.dispatch.transport=rabbitmq")
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(AiJobDispatchTransportConfig.AiJobDispatchTransportValidated.class).value())
					.isEqualTo("rabbitmq");
			});
	}

	@Test
	void unknownTransportValueFailsContextStartup() {
		runner()
			.withPropertyValues("app.ai.dispatch.transport=carrier-pigeon")
			.run(context -> {
				assertThat(context).hasFailed();
				assertThat(context.getStartupFailure())
					.rootCause()
					.isInstanceOf(IllegalStateException.class)
					.hasMessageContaining("app.ai.dispatch.transport")
					.hasMessageContaining("carrier-pigeon");
			});
	}
}
