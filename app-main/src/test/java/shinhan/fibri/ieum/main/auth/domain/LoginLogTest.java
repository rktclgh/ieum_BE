package shinhan.fibri.ieum.main.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.hibernate.annotations.JdbcType;
import org.hibernate.dialect.type.PostgreSQLEnumJdbcType;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;

class LoginLogTest {

	@Test
	void emailLoginCreatesLoginLogForUser() {
		User user = User.createEmailUser(
			"user@example.com",
			"hash",
			"nickname",
			LocalDate.of(2000, 1, 1),
			GenderType.female,
			"KR"
		);

		LoginLog loginLog = LoginLog.emailLogin(user);

		assertThat(loginLog.getUser()).isEqualTo(user);
		assertThat(loginLog.getProvider()).isEqualTo(AuthProvider.email);
		assertThat(loginLog.getLoggedInAt()).isNotNull();
	}

	@Test
	void providerUsesPostgresEnumMapping() throws NoSuchFieldException {
		JdbcType jdbcType = LoginLog.class.getDeclaredField("provider").getAnnotation(JdbcType.class);

		assertThat(jdbcType).isNotNull();
		assertThat(jdbcType.value()).isEqualTo(PostgreSQLEnumJdbcType.class);
	}
}
