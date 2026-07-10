package shinhan.fibri.ieum.main.report.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class RedisReportEventPublisherTest {

	@Test
	void reportCreatedPublishesReportIdToRedisEventChannel() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		RedisReportEventPublisher publisher = new RedisReportEventPublisher(
			redisTemplate,
			new ObjectMapper().findAndRegisterModules()
		);

		publisher.reportCreated(new ReportCreatedEvent(900L));

		verify(redisTemplate).convertAndSend("evt:report.created", "{\"reportId\":900}");
	}
}
