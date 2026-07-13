package shinhan.fibri.ieum.ai.question.analysis;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuestionSnapshotRepository implements QuestionSnapshotRepository {

	private final JdbcClient jdbc;

	public JdbcQuestionSnapshotRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<QuestionInputSnapshot> findActiveByQuestionId(long questionId) {
		if (questionId <= 0) {
			throw new IllegalArgumentException("questionId must be positive");
		}
		return jdbc.sql("""
			SELECT question.title,
			       question.content,
			       ST_Y(pin.location::geometry) AS latitude,
			       ST_X(pin.location::geometry) AS longitude,
			       pin.address,
			       pin.detail_address,
			       pin.label
			FROM questions question
			JOIN pins pin ON pin.pin_id = question.pin_id
			WHERE question.question_id = :questionId
			  AND question.deleted_at IS NULL
			  AND pin.deleted_at IS NULL
			  AND pin.pin_type = 'question'
			""")
			.param("questionId", questionId)
			.query((resultSet, rowNumber) -> new QuestionInputSnapshot(
				resultSet.getString("title"),
				resultSet.getString("content"),
				new StoredLocationSnapshot(
					resultSet.getDouble("latitude"),
					resultSet.getDouble("longitude"),
					resultSet.getString("address"),
					resultSet.getString("detail_address"),
					resultSet.getString("label")
				)
			))
			.optional();
	}
}
