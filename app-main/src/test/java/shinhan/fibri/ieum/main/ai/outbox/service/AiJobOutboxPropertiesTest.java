package shinhan.fibri.ieum.main.ai.outbox.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * relay 파라미터 불변식. spec.md §7.1, PR #255 리뷰 finding 1 + CodeRabbit 재리뷰 finding 1.
 *
 * <p>클레임한 배치의 마지막 row는 {@code confirmTimeout}을 {@code batchSize}번 겪을 수 있다 —
 * 앞선 job들이 전부 타임아웃에 가깝게 걸리는 최악의 경우다. 그 총 시간이 {@code lease}에 그대로
 * 맞닿아 있으면(등호 포함), 마지막 confirm이 lease 만료와 같은 순간 도착할 수 있다 — 그 confirm을
 * 받아 {@code markPublished}를 커밋하는 사이에 lease 복구 스케줄이 먼저 돌아 같은 row를 회수해가면
 * 정산이 펜싱에 막혀 버려지고 이미 발행된 job이 중복 재발행된다. 그래서
 * {@code confirmTimeout × batchSize + SETTLEMENT_HEADROOM ≤ lease}로, DB 정산(커밋 왕복)과
 * 노드 간 시계 스큐를 흡수할 여유 시간을 등호 경계에도 반드시 남겨야 한다.
 */
class AiJobOutboxPropertiesTest {

	private AiJobOutboxProperties properties(Duration lease, int batchSize, Duration confirmTimeout) {
		return new AiJobOutboxProperties("worker-1", lease, 8, batchSize, confirmTimeout, 7, 100);
	}

	@Test
	void acceptsConfigurationWithComfortableHeadroomAboveTheWorstCase() {
		// 32 * 5s + 30s = 190s <= 300s
		assertThatCode(() -> properties(Duration.ofSeconds(300), 32, Duration.ofSeconds(5)))
			.doesNotThrowAnyException();
	}

	@Test
	void acceptsConfigurationWhereWorstCasePlusHeadroomExactlyEqualsTheLease() {
		// 32 * 5s + 30s == 190s
		assertThatCode(() -> properties(Duration.ofSeconds(190), 32, Duration.ofSeconds(5)))
			.doesNotThrowAnyException();
	}

	@Test
	void rejectsConfigurationJustBelowTheHeadroomBoundary() {
		// 32 * 5s + 30s = 190s > 189s
		assertThatThrownBy(() -> properties(Duration.ofSeconds(189), 32, Duration.ofSeconds(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmTimeout")
			.hasMessageContaining("batchSize")
			.hasMessageContaining("lease")
			.hasMessageContaining("headroom");
	}

	@Test
	void rejectsConfigurationAtTheOldNoHeadroomEqualityBoundary() {
		// 32 * 5s == 160s: 헤드룸 도입 전에는 통과했던 경계지만, 이제는 190s > 160s 라서 거부돼야 한다.
		assertThatThrownBy(() -> properties(Duration.ofSeconds(160), 32, Duration.ofSeconds(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("headroom");
	}

	@Test
	void rejectsConfigurationWhereWorstCaseConfirmTimeAloneExceedsTheLease() {
		// 32 * 5s = 160s > 60s (헤드룸을 더하기 전부터 이미 위반)
		assertThatThrownBy(() -> properties(Duration.ofSeconds(60), 32, Duration.ofSeconds(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmTimeout")
			.hasMessageContaining("batchSize")
			.hasMessageContaining("lease");
	}

	@Test
	void productionDefaultsSatisfyTheLeaseInvariantWithHeadroom() {
		// AiJobRabbitConfig 의 실제 기본값(batch-size=32, confirm-timeout=5s, lease=300s).
		// 160s(worst-case) + 30s(headroom) = 190s <= 300s.
		assertThatCode(() -> properties(Duration.ofSeconds(300), 32, Duration.ofSeconds(5)))
			.doesNotThrowAnyException();
	}
}
