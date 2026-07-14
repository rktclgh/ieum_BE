package shinhan.fibri.ieum.ai.question.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClaimedQuestionTaskTest {

	@Test
	void preservesValidatedWorkerFenceForDownstreamCheckpointsAndFinalization() {
		UUID leaseToken = UUID.randomUUID();
		OffsetDateTime leaseUntil = OffsetDateTime.now().plusMinutes(2);

		ClaimedQuestionTask claim = new ClaimedQuestionTask(
			42L,
			" worker-a ",
			leaseToken,
			leaseUntil,
			1
		);

		assertThat(claim.questionId()).isEqualTo(42L);
		assertThat(claim.workerId()).isEqualTo("worker-a");
		assertThat(claim.leaseToken()).isEqualTo(leaseToken);
		assertThat(claim.leaseUntil()).isEqualTo(leaseUntil);
		assertThat(claim.attempts()).isOne();
	}

	@Test
	void rejectsInvalidClaimIdentityOrAttempt() {
		UUID token = UUID.randomUUID();
		OffsetDateTime leaseUntil = OffsetDateTime.now().plusMinutes(2);

		assertThatThrownBy(() -> new ClaimedQuestionTask(0L, "worker", token, leaseUntil, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClaimedQuestionTask(1L, " ", token, leaseUntil, 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClaimedQuestionTask(1L, "worker", null, leaseUntil, 1))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ClaimedQuestionTask(1L, "worker", token, null, 1))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new ClaimedQuestionTask(1L, "worker", token, leaseUntil, 0))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ClaimedQuestionTask(1L, "worker", token, leaseUntil, 6))
			.isInstanceOf(IllegalArgumentException.class);
	}
}
