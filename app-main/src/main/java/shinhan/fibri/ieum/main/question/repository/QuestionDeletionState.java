package shinhan.fibri.ieum.main.question.repository;

import java.time.Instant;

public interface QuestionDeletionState {

	Long getAuthorId();

	Instant getDeletedAt();
}
