package shinhan.fibri.ieum.ai.question.repository;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuestionTaskDiscoveryRepository implements QuestionTaskDiscoveryRepository {

	private static final int MAX_CONFLICT_RETRIES = 3;

	private final JdbcClient jdbc;

	public JdbcQuestionTaskDiscoveryRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public int discover(int batchSize) {
		if (batchSize < 1) {
			throw new IllegalArgumentException("batchSize must be positive");
		}

		int discovered = 0;
		int conflictRetries = 0;
		while (discovered < batchSize) {
			int inserted = insertDiscoverableTasks(batchSize - discovered);
			discovered += inserted;
			if (inserted == 0) {
				if (!hasDiscoverableTask() || conflictRetries++ >= MAX_CONFLICT_RETRIES) {
					break;
				}
			} else {
				conflictRetries = 0;
			}
		}
		return discovered;
	}

	private int insertDiscoverableTasks(int batchSize) {
		return jdbc.sql("""
			INSERT INTO ai_question_tasks (question_id)
			SELECT question.question_id
			FROM questions question
			JOIN pins pin ON pin.pin_id = question.pin_id
			WHERE question.deleted_at IS NULL
			  AND pin.deleted_at IS NULL
			  AND NOT EXISTS (
			      SELECT 1
			      FROM ai_question_tasks task
			      WHERE task.question_id = question.question_id
			  )
			ORDER BY question.question_id
			LIMIT :batchSize
			ON CONFLICT (question_id) DO NOTHING
			""")
			.param("batchSize", batchSize)
			.update();
	}

	private boolean hasDiscoverableTask() {
		return jdbc.sql("""
			SELECT EXISTS (
			    SELECT 1
			    FROM questions question
			    JOIN pins pin ON pin.pin_id = question.pin_id
			    WHERE question.deleted_at IS NULL
			      AND pin.deleted_at IS NULL
			      AND NOT EXISTS (
			          SELECT 1
			          FROM ai_question_tasks task
			          WHERE task.question_id = question.question_id
			      )
			)
			""")
			.query(Boolean.class)
			.single();
	}
}
