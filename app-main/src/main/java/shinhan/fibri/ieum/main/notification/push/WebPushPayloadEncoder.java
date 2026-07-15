package shinhan.fibri.ieum.main.notification.push;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class WebPushPayloadEncoder {

	private static final String ENCODING_FAILURE_MESSAGE = "Failed to encode Web Push payload";

	private final ObjectMapper objectMapper;

	public WebPushPayloadEncoder(ObjectMapper objectMapper) {
		this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
	}

	public byte[] encode(Object payload) {
		try {
			return objectMapper.writeValueAsBytes(Objects.requireNonNull(payload, "payload"));
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException(ENCODING_FAILURE_MESSAGE, exception);
		}
	}
}
