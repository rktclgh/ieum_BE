package shinhan.fibri.ieum.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.UserGrade;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.common.friend.domain.FriendshipStatus;

class SnakeCasePostgreSQLDialectTest {

	private final SnakeCasePostgreSQLDialect dialect = new SnakeCasePostgreSQLDialect();

	@Test
	void rendersEachMappedEnumAsItsSnakeCaseSchemaTypeName() {
		// 좌: 자바 enum 클래스, 우: db/schema.sql 의 CREATE TYPE 이름과 반드시 일치해야 한다.
		assertThat(dialect.getEnumTypeDeclaration(FriendshipStatus.class)).isEqualTo("friendship_status");
		assertThat(dialect.getEnumTypeDeclaration(GenderType.class)).isEqualTo("gender_type");
		assertThat(dialect.getEnumTypeDeclaration(AuthProvider.class)).isEqualTo("auth_provider");
		assertThat(dialect.getEnumTypeDeclaration(RoomType.class)).isEqualTo("room_type");
		assertThat(dialect.getEnumTypeDeclaration(UserRole.class)).isEqualTo("user_role");
		assertThat(dialect.getEnumTypeDeclaration(UserStatus.class)).isEqualTo("user_status");
		assertThat(dialect.getEnumTypeDeclaration(UserGrade.class)).isEqualTo("user_grade");
	}

	@Test
	void rendersEnumConstantWithClassBodyAsDeclaringEnumTypeName() {
		@SuppressWarnings("unchecked")
		Class<? extends Enum<?>> enumConstantClass = (Class<? extends Enum<?>>) StatusWithBody.READY.getClass();

		assertThat(dialect.getEnumTypeDeclaration(enumConstantClass)).isEqualTo("status_with_body");
	}

	@Test
	void convertsPascalCaseIncludingConsecutiveCapitals() {
		assertThat(SnakeCasePostgreSQLDialect.toSnakeCase("FriendshipStatus")).isEqualTo("friendship_status");
		assertThat(SnakeCasePostgreSQLDialect.toSnakeCase("GenderType")).isEqualTo("gender_type");
		assertThat(SnakeCasePostgreSQLDialect.toSnakeCase("AIVerdict")).isEqualTo("ai_verdict");
		assertThat(SnakeCasePostgreSQLDialect.toSnakeCase("PinType")).isEqualTo("pin_type");
	}

	private enum StatusWithBody {
		READY {
			@Override
			String label() {
				return "ready";
			}
		};

		abstract String label();
	}
}
