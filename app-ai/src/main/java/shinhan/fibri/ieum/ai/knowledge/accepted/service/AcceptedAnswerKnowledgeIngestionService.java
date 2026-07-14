package shinhan.fibri.ieum.ai.knowledge.accepted.service;

import java.time.Duration;
import java.util.Objects;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingUnavailableException;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeClaim;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeRepository;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbedder;

public final class AcceptedAnswerKnowledgeIngestionService {

	private final AcceptedAnswerKnowledgeRepository repository;
	private final KnowledgeDocumentEmbedder embedder;
	private final Duration taskLease;
	private final int maxAttempts;
	private final Duration retryDelay;

	public AcceptedAnswerKnowledgeIngestionService(
		AcceptedAnswerKnowledgeRepository repository,
		KnowledgeDocumentEmbedder embedder,
		Duration taskLease,
		int maxAttempts,
		Duration retryDelay
	) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
		this.embedder = Objects.requireNonNull(embedder, "embedder must not be null");
		this.taskLease = Objects.requireNonNull(taskLease, "taskLease must not be null");
		this.maxAttempts = maxAttempts;
		this.retryDelay = Objects.requireNonNull(retryDelay, "retryDelay must not be null");
	}

	public void process(long answerId) {
		repository.claimByAnswerId(answerId, taskLease, maxAttempts)
			.ifPresent(this::embedAndFinalize);
	}

	private void embedAndFinalize(AcceptedAnswerKnowledgeClaim claim) {
		try {
			GeminiEmbedding embedding = embedder.embed(
				claim.document().displayName(),
				claim.document().chunkText()
			);
			repository.finalizeClaim(claim, embedding.values());
		}
		catch (GeminiEmbeddingUnavailableException exception) {
			repository.markEmbeddingFailure(claim, retryDelay, maxAttempts);
		}
	}
}
