package shinhan.fibri.ieum.main.ai.outbox.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * relay 파라미터 불변식. spec.md §7.1, PR #255 리뷰 finding 1.
 *
 * <p>클레임한 배치의 마지막 row는 {@code confirmTimeout}을 {@code batchSize}번 겪을 수 있다 —
 * 앞선 job들이 전부 타임아웃에 가깝게 걸리는 최악의 경우다. 그 총 시간이 {@code lease}를 넘으면
 * lease가 배치 처리 도중 만료되어, 뒤늦게 도착한 confirm이 펜싱에 막혀 버려지고(stale settlement)
 * 이미 발행된 job이 lease 복구에 의해 중복 재발행된다. 그래서
 * {@code confirmTimeout × batchSize ≤ lease}는 기동 시점에 강제해야 하는 불변식이다.
 */
class AiJobOutboxPropertiesTest {

	private AiJobOutboxProperties properties(Duration lease, int batchSize, Duration confirmTimeout) {
		return new AiJobOutboxProperties("worker-1", lease, 8, batchSize, confirmTimeout, 7, 100);
	}

	@Test
	void acceptsConfigurationWhereWorstCaseConfirmTimeIsWithinTheLease() {
		// 32 * 5s = 160s <= 300s
		assertThatCode(() -> properties(Duration.ofSeconds(300), 32, Duration.ofSeconds(5)))
			.doesNotThrowAnyException();
	}

	@Test
	void acceptsConfigurationWhereWorstCaseConfirmTimeExactlyEqualsTheLease() {
		// 32 * 5s == 160s
		assertThatCode(() -> properties(Duration.ofSeconds(160), 32, Duration.ofSeconds(5)))
			.doesNotThrowAnyException();
	}

	@Test
	void rejectsConfigurationWhereWorstCaseConfirmTimeExceedsTheLease() {
		// 32 * 5s = 160s > 60s
		assertThatThrownBy(() -> properties(Duration.ofSeconds(60), 32, Duration.ofSeconds(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmTimeout")
			.hasMessageContaining("batchSize")
			.hasMessageContaining("lease");
	}

	@Test
	void productionDefaultsSatisfyTheLeaseInvariant() {
		// AiJobRabbitConfig 의 실제 기본값(batch-size=32, confirm-timeout=5s, lease=300s).
		assertThatCode(() -> properties(Duration.ofSeconds(300), 32, Duration.ofSeconds(5)))
			.doesNotThrowAnyException();
	}
}
