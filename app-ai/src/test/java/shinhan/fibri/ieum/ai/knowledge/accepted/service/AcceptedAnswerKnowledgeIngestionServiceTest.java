package shinhan.fibri.ieum.ai.knowledge.accepted.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingUnavailableException;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeClaim;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeDocument;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeFinalizeResult;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeRepository;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbedder;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbeddingTextFormatter;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;

class AcceptedAnswerKnowledgeIngestionServiceTest {

	private static final Duration LEASE = Duration.ofMinutes(5);
	private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
	private static final int MAX_ATTEMPTS = 5;

	private final AcceptedAnswerKnowledgeRepository repository =
		mock(AcceptedAnswerKnowledgeRepository.class);
	private final GeminiEmbeddingGateway gateway = mock(GeminiEmbeddingGateway.class);
	private final KnowledgeDocumentEmbedder embedder = new KnowledgeDocumentEmbedder(
		new KnowledgeDocumentEmbeddingTextFormatter(),
		gateway
	);
	private final AcceptedAnswerKnowledgeIngestionService service =
		new AcceptedAnswerKnowledgeIngestionService(
			repository,
			embedder,
			LEASE,
			MAX_ATTEMPTS,
			RETRY_DELAY
		);

	@BeforeEach
	void setUp() {
		when(gateway.embed(anyString())).thenReturn(validEmbedding());
	}

	@Test
	void claimsExactAnswerThenEmbedsAndFinalizesInOrder() {
		AcceptedAnswerKnowledgeClaim claim = claim(42L);
		when(repository.claimByAnswerId(42L, LEASE, MAX_ATTEMPTS))
			.thenReturn(Optional.of(claim));
		when(repository.finalizeClaim(claim, validEmbedding().values()))
			.thenReturn(AcceptedAnswerKnowledgeFinalizeResult.READY);

		service.process(42L);

		InOrder order = inOrder(repository, gateway);
		order.verify(repository).claimByAnswerId(42L, LEASE, MAX_ATTEMPTS);
		order.verify(gateway).embed("title: 테스트 질문 | text: 질문 제목: 테스트 질문\n채택 답변: 테스트 답변");
		order.verify(repository).finalizeClaim(claim, validEmbedding().values());
		verify(repository, never()).markEmbeddingFailure(claim, RETRY_DELAY, MAX_ATTEMPTS);
	}

	@Test
	void stopsWithoutEmbeddingWhenExactAnswerCannotBeClaimed() {
		when(repository.claimByAnswerId(42L, LEASE, MAX_ATTEMPTS)).thenReturn(Optional.empty());

		service.process(42L);

		verify(repository).claimByAnswerId(42L, LEASE, MAX_ATTEMPTS);
		verifyNoInteractions(gateway);
		verify(repository, never()).finalizeClaim(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.anyList()
		);
	}

	@Test
	void persistsSafeFailureWhenEmbeddingProviderIsUnavailable() {
		AcceptedAnswerKnowledgeClaim claim = claim(42L);
		when(repository.claimByAnswerId(42L, LEASE, MAX_ATTEMPTS))
			.thenReturn(Optional.of(claim));
		when(gateway.embed(anyString())).thenThrow(new GeminiEmbeddingUnavailableException());

		service.process(42L);

		verify(repository).markEmbeddingFailure(claim, RETRY_DELAY, MAX_ATTEMPTS);
		verify(repository, never()).finalizeClaim(
			org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.anyList()
		);
	}

	@Test
	void doesNotMisclassifyFinalizeDatabaseFailureAsEmbeddingFailure() {
		AcceptedAnswerKnowledgeClaim claim = claim(42L);
		IllegalStateException databaseFailure = new IllegalStateException("database unavailable");
		when(repository.claimByAnswerId(42L, LEASE, MAX_ATTEMPTS))
			.thenReturn(Optional.of(claim));
		when(repository.finalizeClaim(claim, validEmbedding().values()))
			.thenThrow(databaseFailure);

		assertThatThrownBy(() -> service.process(42L)).isSameAs(databaseFailure);

		verify(repository, never()).markEmbeddingFailure(claim, RETRY_DELAY, MAX_ATTEMPTS);
	}

	@Test
	void hasNoOuterTransactionBoundaryAroundProviderWork() throws Exception {
		assertThat(AcceptedAnswerKnowledgeIngestionService.class
			.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
			.isNull();
		assertThat(AcceptedAnswerKnowledgeIngestionService.class
			.getMethod("process", long.class)
			.getAnnotation(org.springframework.transaction.annotation.Transactional.class))
			.isNull();
	}

	private static AcceptedAnswerKnowledgeClaim claim(long answerId) {
		return new AcceptedAnswerKnowledgeClaim(
			100L,
			200L,
			answerId,
			UUID.fromString("00000000-0000-0000-0000-000000000042"),
			OffsetDateTime.parse("2026-07-14T12:05:00+09:00"),
			1,
			new AcceptedAnswerKnowledgeDocument(
				"테스트 질문",
				"a".repeat(64),
				"질문 제목: 테스트 질문\n채택 답변: 테스트 답변",
				GeoScope.general,
				RegionContext.empty(),
				37.5665,
				126.9780
			)
		);
	}

	private static GeminiEmbedding validEmbedding() {
		List<Float> values = new ArrayList<>();
		for (int index = 0; index < GeminiEmbedding.DIMENSIONS; index++) {
			values.add(index / 1000.0f);
		}
		return new GeminiEmbedding(GeminiEmbedding.MODEL, values);
	}
}
