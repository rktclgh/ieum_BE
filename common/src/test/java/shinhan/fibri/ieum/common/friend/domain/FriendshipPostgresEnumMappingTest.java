package shinhan.fibri.ieum.common.friend.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.junit.jupiter.api.Test;

class FriendshipPostgresEnumMappingTest {

	@Test
	void mapsFriendshipStatusToPostgresNamedEnum() throws NoSuchFieldException {
		Field field = Friendship.class.getDeclaredField("status");
		JdbcType jdbcType = field.getAnnotation(JdbcType.class);

		assertThat(jdbcType).isNotNull();
		assertThat(jdbcType.value()).isEqualTo(PostgreSQLEnumJdbcType.class);
	}
}
