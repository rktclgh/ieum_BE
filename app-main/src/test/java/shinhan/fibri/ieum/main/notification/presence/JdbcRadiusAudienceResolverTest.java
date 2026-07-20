package shinhan.fibri.ieum.main.notification.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcRadiusAudienceResolverTest {

	@Test
	void usesConstantTenKilometerPrefilterBeforePerUserRadiusFilter() {
		CapturingNamedParameterJdbcTemplate jdbcTemplate = new CapturingNamedParameterJdbcTemplate();
		JdbcRadiusAudienceResolver resolver = new JdbcRadiusAudienceResolver(jdbcTemplate);

		resolver.resolve(37.5665, 126.9780, NotificationCategory.question, 1L, Set.of());

		String sql = jdbcTemplate.capturedSql();
		assertThat(sql)
			.contains("10000.0")
			.contains("s.notify_radius_km * 1000.0");
		assertThat(sql.indexOf("10000.0"))
			.isLessThan(sql.indexOf("s.notify_radius_km * 1000.0"));
	}

	private static class CapturingNamedParameterJdbcTemplate extends NamedParameterJdbcTemplate {

		private String capturedSql;

		CapturingNamedParameterJdbcTemplate() {
			super(mock(DataSource.class));
		}

		@Override
		public <T> List<T> queryForList(String sql, Map<String, ?> paramMap, Class<T> elementType) {
			this.capturedSql = sql;
			return List.of();
		}

		String capturedSql() {
			return capturedSql;
		}
	}
}
