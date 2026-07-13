package shinhan.fibri.ieum.ai.question.analysis;

import java.util.Optional;

public interface QuestionSnapshotRepository {

	Optional<QuestionInputSnapshot> findActiveByQuestionId(long questionId);
}
