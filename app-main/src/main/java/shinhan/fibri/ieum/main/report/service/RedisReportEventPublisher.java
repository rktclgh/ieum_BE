package shinhan.fibri.ieum.main.report.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisReportEventPublisher implements ReportEventPublisher {

	private static final String CHANNEL = "evt:report.created";

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	public RedisReportEventPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	@Override
	public void reportCreated(ReportCreatedEvent event) {
		try {
			redisTemplate.convertAndSend(CHANNEL, objectMapper.writeValueAsString(event));
		} catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize report created event", exception);
		}
	}
}
