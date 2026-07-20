package shinhan.fibri.ieum.main.notification.presence;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcRadiusAudienceResolver implements RadiusAudienceResolver {

	private final NamedParameterJdbcTemplate jdbcTemplate;

	public JdbcRadiusAudienceResolver(NamedParameterJdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public List<Long> resolve(
		double latitude,
		double longitude,
		NotificationCategory category,
		Long authorId,
		Set<Long> blockedUserIds
	) {
		Objects.requireNonNull(category, "category must not be null");
		Objects.requireNonNull(authorId, "authorId must not be null");
		Objects.requireNonNull(blockedUserIds, "blockedUserIds must not be null");

		Map<String, Object> parameters = new HashMap<>();
		parameters.put("latitude", latitude);
		parameters.put("longitude", longitude);
		parameters.put("authorId", authorId);
		parameters.put("blockedUserIds", blockedUserIds.isEmpty() ? List.of(-1L) : List.copyOf(blockedUserIds));
		parameters.put("hasBlockedUserIds", !blockedUserIds.isEmpty());

		return jdbcTemplate.queryForList("""
			SELECT u.user_id
			FROM users u
			JOIN user_settings s ON s.user_id = u.user_id
			WHERE u.deleted_at IS NULL
			  AND u.status = 'active'
			  AND u.user_id <> :authorId
			  AND (:hasBlockedUserIds = FALSE OR u.user_id NOT IN (:blockedUserIds))
			  AND u.last_location IS NOT NULL
			  AND s.notify_all = TRUE
			  AND %s = TRUE
			  AND ST_DWithin(
				  u.last_location,
				  ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
				  10000.0
			  )
			  AND ST_DWithin(
				  u.last_location,
				  ST_SetSRID(ST_MakePoint(:longitude, :latitude), 4326)::geography,
				  s.notify_radius_km * 1000.0
			  )
			ORDER BY u.user_id
			""".formatted(categoryFlagColumn(category)), parameters, Long.class);
	}

	private String categoryFlagColumn(NotificationCategory category) {
		return switch (category) {
			case question -> "s.notify_question";
			case meeting -> "s.notify_meeting";
		};
	}
}
