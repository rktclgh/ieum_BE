package shinhan.fibri.ieum.ai.question.checkpoint;

import java.time.Duration;
import java.util.Optional;
import shinhan.fibri.ieum.ai.question.analysis.QueryAnalysis;
import shinhan.fibri.ieum.ai.question.embedding.QuestionEmbedding;
import shinhan.fibri.ieum.ai.question.repository.ClaimedQuestionTask;

public interface QuestionCheckpointRepository {

	Optional<LockedQuestionCheckpoint> lockCurrentFence(ClaimedQuestionTask claim);

	boolean lockActiveQuestion(long questionId);

	boolean cancelCurrentFence(ClaimedQuestionTask claim);

	boolean saveAnalysis(
		ClaimedQuestionTask claim,
		QueryAnalysis analysis,
		Duration leaseExtension
	);

	boolean saveEmbedding(
		ClaimedQuestionTask claim,
		QuestionEmbedding embedding,
		Duration leaseExtension
	);

	boolean advanceStage(
		ClaimedQuestionTask claim,
		QuestionTaskStage expectedStage,
		QuestionTaskStage nextStage,
		Duration leaseExtension
	);
}
