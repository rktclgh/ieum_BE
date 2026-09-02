package shinhan.fibri.ieum.common.ai.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AiJobTopologyTest {

	@Test
	void definesExchangeNamesFromSpec() {
		assertThat(AiJobTopology.EXCHANGE_JOBS).isEqualTo("ieum.ai.jobs");
		assertThat(AiJobTopology.EXCHANGE_RESULTS).isEqualTo("ieum.ai.results");
		assertThat(AiJobTopology.EXCHANGE_RETRY).isEqualTo("ieum.ai.retry");
		assertThat(AiJobTopology.EXCHANGE_DLX).isEqualTo("ieum.ai.dlx");
	}

	@Test
	void definesQueueNamesFromSpec() {
		assertThat(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH).isEqualTo("ieum.ai.question-answer.dispatch");
		assertThat(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY)
			.isEqualTo("ieum.ai.question-answer.dispatch.retry");
		assertThat(AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ)
			.isEqualTo("ieum.ai.question-answer.dispatch.dlq");
		assertThat(AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST).isEqualTo("ieum.ai.accepted-answer.ingest");
		assertThat(AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_RETRY)
			.isEqualTo("ieum.ai.accepted-answer.ingest.retry");
		assertThat(AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_DLQ).isEqualTo("ieum.ai.accepted-answer.ingest.dlq");
		assertThat(AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED).isEqualTo("ieum.main.question-answer.completed");
		assertThat(AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_RETRY)
			.isEqualTo("ieum.main.question-answer.completed.retry");
		assertThat(AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_DLQ)
			.isEqualTo("ieum.main.question-answer.completed.dlq");
	}

	@Test
	void queueNamesAreAllUnique() {
		List<String> queueNames = List.of(
			AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH,
			AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_RETRY,
			AiJobTopology.QUEUE_QUESTION_ANSWER_DISPATCH_DLQ,
			AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST,
			AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_RETRY,
			AiJobTopology.QUEUE_ACCEPTED_ANSWER_INGEST_DLQ,
			AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED,
			AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_RETRY,
			AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED_DLQ
		);

		assertThat(Set.copyOf(queueNames)).hasSize(queueNames.size());
	}

	@Test
	void definesRoutingKeysFromSpec() {
		assertThat(AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH).isEqualTo("ai.question-answer.dispatch");
		assertThat(AiJobTopology.ROUTING_KEY_ACCEPTED_ANSWER_INGEST).isEqualTo("ai.accepted-answer.ingest");
		assertThat(AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_COMPLETED).isEqualTo("ai.question-answer.completed");
	}

	@Test
	void definesRetryAndDeliveryConstants() {
		assertThat(AiJobTopology.MAX_DELIVERY_ATTEMPTS).isEqualTo(5);
		assertThat(AiJobTopology.RETRY_TTL_MS).isEqualTo(30_000);
		assertThat(AiJobTopology.SCHEMA_VERSION).isEqualTo(1);
		assertThat(AiJobTopology.PREFETCH).isEqualTo(1);
	}
}
