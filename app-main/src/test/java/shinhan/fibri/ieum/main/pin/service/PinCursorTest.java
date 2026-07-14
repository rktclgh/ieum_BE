package shinhan.fibri.ieum.main.pin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.pin.exception.InvalidPinRequestException;

class PinCursorTest {

	@Test
	void encodesAndDecodesPinId() {
		String cursor = PinCursor.encode(123L);

		assertThat(PinCursor.decode(cursor)).isEqualTo(123L);
	}

	@Test
	void encodesNullAsNull() {
		assertThat(PinCursor.encode(null)).isNull();
	}

	@Test
	void decodesNullAsNull() {
		assertThat(PinCursor.decode(null)).isNull();
	}

	@Test
	void rejectsMalformedCursor() {
		assertThatThrownBy(() -> PinCursor.decode("not-base64"))
			.isInstanceOf(InvalidPinRequestException.class)
			.hasMessage("Invalid cursor");
	}
}
