package shinhan.fibri.ieum.main.ai.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * backoff 수식 경계. spec.md §7.5 — {@code next_attempt_at = now() + min(2^(attempts-1), 60) seconds}.
 *
 * <p>클레임이 {@code attempts}를 먼저 1 증가시키므로, 첫 발행 실패 시점의 {@code attempts}는 1이고
 * 그때의 대기는 1초여야 한다. 상한은 60초다.
 */
class AiJobOutboxBackoffTest {

	@ParameterizedTest(name = "attempts={0} -> {1}s")
	@CsvSource({
		"1, 1",
		"2, 2",
		"3, 4",
		"4, 8",
		"5, 16",
		"6, 32",
		"7, 60",
		"8, 60",
		"20, 60"
	})
	void doublesUntilTheSixtySecondCeiling(int attempts, long expectedSeconds) {
		assertThat(AiJobOutboxRelay.backoffSeconds(attempts)).isEqualTo(expectedSeconds);
	}

	@Test
	void rejectsNonPositiveAttempts() {
		assertThatThrownBy(() -> AiJobOutboxRelay.backoffSeconds(0))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
