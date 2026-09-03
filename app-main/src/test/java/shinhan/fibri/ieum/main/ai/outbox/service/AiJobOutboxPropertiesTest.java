package shinhan.fibri.ieum.main.ai.outbox.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * relay 파라미터 불변식. spec.md §7.1, PR #255 리뷰 finding 1 + CodeRabbit 재리뷰 finding 1 +
 * CodeRabbit PR #257 finding 4.
 *
 * <p>클레임한 배치의 마지막 row는 {@code confirmTimeout}을 {@code batchSize}번 겪을 수 있다 —
 * 앞선 job들이 전부 타임아웃에 가깝게 걸리는 최악의 경우다. row마다 confirm 대기 직후 정산
 * UPDATE(markPublished/markRetry/markDead)의 DB 왕복도 시간이 들고, lease 갱신을 구현하지 않은
 * 이상 이 시간도 batchSize번 누적돼 lease 를 갉아먹는다({@code SETTLEMENT_BUDGET_PER_ROW}).
 * 그 총 시간이 {@code lease}에 그대로 맞닿아 있으면(등호 포함), 마지막 confirm+정산이 lease 만료와
 * 같은 순간 끝날 수 있다 — 그 사이 lease 복구 스케줄이 먼저 돌아 같은 row를 회수해가면 정산이
 * 펜싱에 막혀 버려지고 이미 발행된 job이 중복 재발행된다. 그래서
 * {@code (confirmTimeout + SETTLEMENT_BUDGET_PER_ROW) × batchSize + SETTLEMENT_HEADROOM ≤ lease}로,
 * row별 정산 왕복과 배치 전체의 커밋 왕복·노드 간 시계 스큐를 모두 흡수할 여유를 등호 경계에도
 * 반드시 남겨야 한다.
 */
class AiJobOutboxPropertiesTest {

	private AiJobOutboxProperties properties(Duration lease, int batchSize, Duration confirmTimeout) {
		return new AiJobOutboxProperties("worker-1", lease, 8, batchSize, confirmTimeout, 7, 100);
	}

	@Test
	void acceptsConfigurationWithComfortableHeadroomAboveTheWorstCase() {
		// 32 * (5s + 0.5s) + 30s = 206s <= 300s
		assertThatCode(() -> properties(Duration.ofSeconds(300), 32, Duration.ofSeconds(5)))
			.doesNotThrowAnyException();
	}

	@Test
	void acceptsConfigurationWhereWorstCasePlusHeadroomExactlyEqualsTheLease() {
		// 32 * (5s + 0.5s) + 30s == 206s
		assertThatCode(() -> properties(Duration.ofSeconds(206), 32, Duration.ofSeconds(5)))
			.doesNotThrowAnyException();
	}

	@Test
	void rejectsConfigurationJustBelowTheHeadroomBoundary() {
		// 32 * (5s + 0.5s) + 30s = 206s > 205s
		assertThatThrownBy(() -> properties(Duration.ofSeconds(205), 32, Duration.ofSeconds(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmTimeout")
			.hasMessageContaining("batchSize")
			.hasMessageContaining("lease")
			.hasMessageContaining("headroom")
			.hasMessageContaining("settlement budget");
	}

	@Test
	void rejectsConfigurationAtThePreviousHeadroomOnlyBoundary() {
		// 32 * 5s + 30s == 190s: per-row 정산 예산 도입 전에는 통과했던 경계지만, 이제는
		// 206s > 190s 라서 거부돼야 한다.
		assertThatThrownBy(() -> properties(Duration.ofSeconds(190), 32, Duration.ofSeconds(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("settlement budget");
	}

	@Test
	void rejectsConfigurationAtTheOldNoHeadroomEqualityBoundary() {
		// 32 * 5s == 160s: 헤드룸·정산 예산 도입 전에는 통과했던 경계지만, 이제는
		// 206s > 160s 라서 거부돼야 한다.
		assertThatThrownBy(() -> properties(Duration.ofSeconds(160), 32, Duration.ofSeconds(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("settlement budget");
	}

	@Test
	void rejectsConfigurationWhereWorstCaseConfirmTimeAloneExceedsTheLease() {
		// 32 * 5s = 160s > 60s (헤드룸·정산 예산을 더하기 전부터 이미 위반)
		assertThatThrownBy(() -> properties(Duration.ofSeconds(60), 32, Duration.ofSeconds(5)))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("confirmTimeout")
			.hasMessageContaining("batchSize")
			.hasMessageContaining("lease");
	}

	@Test
	void productionDefaultsSatisfyTheLeaseInvariantWithHeadroomAndSettlementBudget() {
		// AiJobRabbitConfig 의 실제 기본값(batch-size=32, confirm-timeout=5s, lease=300s).
		// 32 * 5.5s(206s) <= 300s.
		assertThatCode(() -> properties(Duration.ofSeconds(300), 32, Duration.ofSeconds(5)))
			.doesNotThrowAnyException();
	}
}
