package shinhan.fibri.ieum.main.notification.presence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PresenceSeedRepository {

	private final JdbcTemplate jdbcTemplate;

	public PresenceSeedRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Optional<PresenceSeed> findSeedByUserId(Long userId) {
		return jdbcTemplate.query(
			"""
				SELECT ST_Y(u.last_location::geometry) AS latitude,
				       ST_X(u.last_location::geometry) AS longitude,
				       s.notify_all, s.notify_question, s.notify_meeting, s.notify_radius_km
				FROM users u
				JOIN user_settings s ON s.user_id = u.user_id
				WHERE u.user_id = ? AND u.deleted_at IS NULL
				""",
			(resultSet, rowNumber) -> seed(resultSet),
			userId
		).stream().findFirst();
	}

	private PresenceSeed seed(ResultSet resultSet) throws SQLException {
		return new PresenceSeed(
			(Double) resultSet.getObject("latitude"),
			(Double) resultSet.getObject("longitude"),
			resultSet.getBoolean("notify_all"),
			resultSet.getBoolean("notify_question"),
			resultSet.getBoolean("notify_meeting"),
			resultSet.getInt("notify_radius_km")
		);
	}
}
