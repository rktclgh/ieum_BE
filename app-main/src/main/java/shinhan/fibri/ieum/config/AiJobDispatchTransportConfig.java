package shinhan.fibri.ieum.config;

import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@code app.ai.dispatch.transport} 값을 기동 시점에 검증한다. "알 수 없는 값 → 컨텍스트
 * 기동 실패" 요구사항 — 오타로 조용히 {@code http}(기본값)로 떨어지는 사고를 막는다.
 *
 * <p>허용값·각 값의 의미는 {@code application.properties}의 {@code app.ai.dispatch.transport} 주석이
 * 단일 진실 원천이다 — 여기서는 반복하지 않는다.
 *
 * <p>이 빈은 어떤 {@code @ConditionalOnProperty}에도 걸리지 않고 항상 생성된다 — 생성자에서 즉시
 * 검증하므로, 값이 잘못됐으면 다른 빈이 만들어지기 전에 {@link IllegalStateException}으로 컨텍스트
 * 기동을 막는다.
 */
@Configuration
public class AiJobDispatchTransportConfig {

	static final String PROPERTY = "app.ai.dispatch.transport";
	static final String HTTP = "http";
	static final String RABBITMQ = "rabbitmq";
	private static final Set<String> ALLOWED_VALUES = Set.of(HTTP, RABBITMQ);

	@Bean
	AiJobDispatchTransportValidated aiJobDispatchTransportValidated(
		@Value("${" + PROPERTY + ":" + HTTP + "}") String transport
	) {
		if (!ALLOWED_VALUES.contains(transport)) {
			throw new IllegalStateException(
				"Unknown " + PROPERTY + " value: '" + transport + "' (expected '" + HTTP + "' or '" + RABBITMQ + "')"
			);
		}
		return new AiJobDispatchTransportValidated(transport);
	}

	/** 검증이 끝났다는 마커. 다른 빈이 이 타입을 주입받아 초기화 순서를 강제할 필요는 없다 — 존재만으로 충분하다. */
	record AiJobDispatchTransportValidated(String value) {
	}
}
