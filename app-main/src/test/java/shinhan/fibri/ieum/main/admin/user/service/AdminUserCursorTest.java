package shinhan.fibri.ieum.main.admin.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.admin.user.exception.InvalidAdminCursorException;

class AdminUserCursorTest {

	@Test
	void encodesAndDecodesUserId() {
		String cursor = AdminUserCursor.encode(123L);

		assertThat(AdminUserCursor.decode(cursor)).isEqualTo(123L);
	}

	@Test
	void blankCursorDecodesToNull() {
		assertThat(AdminUserCursor.decode(null)).isNull();
		assertThat(AdminUserCursor.decode(" ")).isNull();
	}

	@Test
	void invalidCursorThrowsInvalidAdminCursorException() {
		assertThatThrownBy(() -> AdminUserCursor.decode("not-base64"))
			.isInstanceOf(InvalidAdminCursorException.class)
			.hasMessage("Invalid cursor");
	}
}
