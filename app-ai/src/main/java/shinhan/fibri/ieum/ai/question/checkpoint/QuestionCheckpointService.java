package shinhan.fibri.ieum.ai.question.checkpoint;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.ai.question.analysis.QueryAnalysis;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbedding;
import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;

@Service
public class QuestionCheckpointService {

	private static final int EMBEDDING_DIMENSIONS = 768;
	private static final String EMBEDDING_MODEL = "gemini-embedding-2";

	private final QuestionCheckpointRepository repository;

	public QuestionCheckpointService(QuestionCheckpointRepository repository) {
		this.repository = Objects.requireNonNull(repository, "repository must not be null");
	}

	@Transactional
	public QuestionCheckpointResult saveAnalysis(
		ClaimedQuestionTask claim,
		QueryAnalysis analysis,
		Duration leaseExtension
	) {
		Objects.requireNonNull(analysis, "analysis must not be null");
		validateAnalysisVersion(analysis.analysisVersion());
		validateInputs(claim, leaseExtension);
		return applyCheckpoint(
			claim,
			() -> repository.saveAnalysis(claim, analysis, leaseExtension)
		);
	}

	@Transactional
	public QuestionCheckpointResult saveEmbedding(
		ClaimedQuestionTask claim,
		QuestionEmbedding embedding,
		Duration leaseExtension
	) {
		validateEmbedding(embedding);
		validateInputs(claim, leaseExtension);
		return applyCheckpoint(
			claim,
			() -> repository.saveEmbedding(claim, embedding, leaseExtension)
		);
	}

	@Transactional
	public QuestionCheckpointResult guardAndAdvance(
		ClaimedQuestionTask claim,
		QuestionTaskStage expectedStage,
		QuestionTaskStage nextStage,
		Duration leaseExtension
	) {
		Objects.requireNonNull(expectedStage, "expectedStage must not be null");
		Objects.requireNonNull(nextStage, "nextStage must not be null");
		if (!expectedStage.canAdvanceTo(nextStage)) {
			throw new IllegalArgumentException(
				"Unsupported question task stage transition: " + expectedStage + " -> " + nextStage
			);
		}
		validateInputs(claim, leaseExtension);
		return applyCheckpoint(
			claim,
			() -> repository.advanceStage(claim, expectedStage, nextStage, leaseExtension)
		);
	}

	private QuestionCheckpointResult applyCheckpoint(
		ClaimedQuestionTask claim,
		BooleanSupplier update
	) {
		LockedQuestionCheckpoint locked = repository.lockCurrentFence(claim)
			.orElseThrow(() -> stale(claim));
		if (locked.cancellationRequested()) {
			return cancel(claim);
		}
		boolean activeQuestion = repository.lockActiveQuestion(claim.questionId());
		if (!activeQuestion) {
			return cancel(claim);
		}
		if (!update.getAsBoolean()) {
			throw stale(claim);
		}
		return QuestionCheckpointResult.APPLIED;
	}

	private QuestionCheckpointResult cancel(ClaimedQuestionTask claim) {
		if (!repository.cancelCurrentFence(claim)) {
			throw stale(claim);
		}
		return QuestionCheckpointResult.CANCELLED;
	}

	private void validateInputs(ClaimedQuestionTask claim, Duration leaseExtension) {
		Objects.requireNonNull(claim, "claim must not be null");
		if (leaseExtension == null
			|| leaseExtension.isNegative()
			|| leaseExtension.isZero()
			|| leaseExtension.toSeconds() < 1) {
			throw new IllegalArgumentException("leaseExtension must be at least one second");
		}
	}

	private void validateAnalysisVersion(String analysisVersion) {
		if (analysisVersion.length() > 80) {
			throw new IllegalArgumentException("analysisVersion must contain at most 80 characters");
		}
	}

	private void validateEmbedding(QuestionEmbedding embedding) {
		Objects.requireNonNull(embedding, "embedding must not be null");
		if (!EMBEDDING_MODEL.equals(embedding.model())) {
			throw new IllegalArgumentException("embedding model must be gemini-embedding-2");
		}
		List<Float> values = embedding.values();
		if (values.size() != EMBEDDING_DIMENSIONS) {
			throw new IllegalArgumentException("embedding must contain exactly 768 values");
		}
		for (Float value : values) {
			if (value == null || !Float.isFinite(value)) {
				throw new IllegalArgumentException("embedding values must be finite");
			}
		}
	}

	private StaleQuestionCheckpointException stale(ClaimedQuestionTask claim) {
		return new StaleQuestionCheckpointException(claim.questionId());
	}
}
