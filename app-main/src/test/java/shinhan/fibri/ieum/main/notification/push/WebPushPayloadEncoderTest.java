package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WebPushPayloadEncoderTest {

	@Test
	void replacesSerializationDetailsWithConstantSafeFailure() throws Exception {
		ObjectMapper objectMapper = mock(ObjectMapper.class);
		Object sensitivePayload = new Object();
		when(objectMapper.writeValueAsBytes(sensitivePayload))
			.thenThrow(new JsonProcessingException("sensitive serialization details") { });

		WebPushPayloadEncoder encoder = new WebPushPayloadEncoder(objectMapper);

		assertThatThrownBy(() -> encoder.encode(sensitivePayload))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Failed to encode Web Push payload")
			.satisfies(exception -> assertThat(exception.getMessage()).doesNotContain("sensitive"));
	}
}
