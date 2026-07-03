package shinhan.fibri.ieum.common.auth.domain;

import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

class UserPostgresEnumMappingTest {

	@Test
	void mapsUserEnumFieldsToPostgresNamedEnums() throws NoSuchFieldException {
		assertPostgresEnum("provider");
		assertPostgresEnum("role");
		assertPostgresEnum("status");
		assertPostgresEnum("grade");
		assertPostgresEnum("gender");
	}

	private void assertPostgresEnum(String fieldName) throws NoSuchFieldException {
		Field field = User.class.getDeclaredField(fieldName);
		JdbcType jdbcType = field.getAnnotation(JdbcType.class);

		assertThat(jdbcType).isNotNull();
		assertThat(jdbcType.value()).isEqualTo(PostgreSQLEnumJdbcType.class);
	}
}
