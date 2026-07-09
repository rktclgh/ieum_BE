package shinhan.fibri.ieum.common.chat.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.junit.jupiter.api.Test;

class ChatRoomPostgresEnumMappingTest {

	@Test
	void mapsRoomTypeToPostgresNamedEnum() throws NoSuchFieldException {
		Field field = ChatRoom.class.getDeclaredField("roomType");
		JdbcType jdbcType = field.getAnnotation(JdbcType.class);

		assertThat(jdbcType).isNotNull();
		assertThat(jdbcType.value()).isEqualTo(PostgreSQLEnumJdbcType.class);
	}
}
