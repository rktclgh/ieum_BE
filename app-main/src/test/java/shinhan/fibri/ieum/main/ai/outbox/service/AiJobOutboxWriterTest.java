package shinhan.fibri.ieum.main.ai.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.main.ai.outbox.entity.AiJobOutbox;
import shinhan.fibri.ieum.main.ai.outbox.repository.AiJobOutboxRepository;

class AiJobOutboxWriterTest {

	private final AiJobOutboxRepository repository = mock(AiJobOutboxRepository.class);
	private final ObjectMapper objectMapper = new ObjectMapper();
	private final AiJobOutboxWriter writer = new AiJobOutboxWriter(repository, objectMapper);

	@Test
	void enqueueQuestionAnswerDispatchSavesPendingOutboxRowWithQuestionPayload() throws Exception {
		writer.enqueueQuestionAnswerDispatch(42L, Reason.CREATED);

		ArgumentCaptor<AiJobOutbox> captor = ArgumentCaptor.forClass(AiJobOutbox.class);
		verify(repository).save(captor.capture());
		AiJobOutbox saved = captor.getValue();

		assertThat(saved.getJobType()).isEqualTo("question_answer_dispatch");
		assertThat(saved.getRoutingKey()).isEqualTo(AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_DISPATCH);
		assertThat(saved.getSchemaVersion()).isEqualTo((short) 1);
		assertThat(saved.getJobId()).isNotNull();
		assertThat(saved.getJobKey()).isEqualTo(42L);
		assertThat(saved.getStatus()).isEqualTo("pending");

		JsonNode payload = objectMapper.readTree(saved.getPayload());
		assertThat(payload.get("questionId").asLong()).isEqualTo(42L);
		assertThat(payload.get("reason").asText()).isEqualTo("created");
		assertThat(payload.get("jobId").asText()).isEqualTo(saved.getJobId().toString());
	}

	@Test
	void enqueueQuestionAnswerDispatchWithRegeneratedReasonWritesRegeneratedPayload() throws Exception {
		writer.enqueueQuestionAnswerDispatch(7L, Reason.REGENERATED);

		ArgumentCaptor<AiJobOutbox> captor = ArgumentCaptor.forClass(AiJobOutbox.class);
		verify(repository).save(captor.capture());
		JsonNode payload = objectMapper.readTree(captor.getValue().getPayload());
		assertThat(payload.get("reason").asText()).isEqualTo("regenerated");
	}

	@Test
	void enqueueQuestionAnswerDispatchRejectsNonPositiveQuestionId() {
		assertThatThrownBy(() -> writer.enqueueQuestionAnswerDispatch(0L, Reason.CREATED))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> writer.enqueueQuestionAnswerDispatch(-1L, Reason.CREATED))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(repository);
	}

	@Test
	void enqueueAcceptedAnswerKnowledgeSavesPendingOutboxRowWithAnswerPayload() throws Exception {
		writer.enqueueAcceptedAnswerKnowledge(99L);

		ArgumentCaptor<AiJobOutbox> captor = ArgumentCaptor.forClass(AiJobOutbox.class);
		verify(repository).save(captor.capture());
		AiJobOutbox saved = captor.getValue();

		assertThat(saved.getJobType()).isEqualTo("accepted_answer_knowledge_ingest");
		assertThat(saved.getRoutingKey()).isEqualTo(AiJobTopology.ROUTING_KEY_ACCEPTED_ANSWER_INGEST);
		assertThat(saved.getSchemaVersion()).isEqualTo((short) 1);
		assertThat(saved.getJobId()).isNotNull();
		assertThat(saved.getJobKey()).isEqualTo(99L);

		JsonNode payload = objectMapper.readTree(saved.getPayload());
		assertThat(payload.get("answerId").asLong()).isEqualTo(99L);
		assertThat(payload.get("jobId").asText()).isEqualTo(saved.getJobId().toString());
	}

	@Test
	void enqueueAcceptedAnswerKnowledgeRejectsNonPositiveAnswerId() {
		assertThatThrownBy(() -> writer.enqueueAcceptedAnswerKnowledge(0L))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> writer.enqueueAcceptedAnswerKnowledge(-5L))
			.isInstanceOf(IllegalArgumentException.class);
		verifyNoInteractions(repository);
	}

	@Test
	void enqueueQuestionAnswerDispatchRejectsNullReason() {
		assertThatThrownBy(() -> writer.enqueueQuestionAnswerDispatch(1L, null))
			.isInstanceOf(NullPointerException.class);
	}
}
