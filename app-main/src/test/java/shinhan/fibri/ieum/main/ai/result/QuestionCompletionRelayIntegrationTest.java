package shinhan.fibri.ieum.main.ai.result;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;
import shinhan.fibri.ieum.testsupport.AiJobRabbitContainer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

/**
 * 완료 결과 경로를 실제 Testcontainers Postgres + RabbitMQ 위에서 끝단으로 검증한다. spec.md §8.4/§8.5,
 * 브리프 "먼저 쓸 테스트" {@code QuestionCompletionRelayIntegrationTest}.
 *
 * <p><b>모듈 경계에 대한 노트</b>: app-ai 와 app-main 은 서로 import 하지 않는 별개 Gradle 모듈이다
 * ({@code :common}에만 공통 의존). 그래서 이 테스트는 app-ai 의 {@code QuestionCompletionOutboxRelay}를
 * 직접 호출하는 대신, 그 relay 가 발행했을 것과 <b>동일한 wire 포맷</b>의 메시지를
 * {@code ieum.ai.results} 익스체인지에 직접 발행한다({@code AiJobPublisherConfirmIntegrationTest}가
 * publisher confirms 자체를 이미 증명했다). app-ai 쪽 발행 로직 자체는
 * {@code RabbitQuestionCompletionCallbackClientTest}(confirm/nack/timeout 분기)와
 * {@code JdbcQuestionCompletionCallbackRepositoryBatchTest}(ACK 된 티켓 제외)가 각각 증명한다.
 * 이 테스트가 증명하는 것은 <b>app-main 소비 측 전체</b> — 실제 브로커에서 받은 메시지가 실제 DB
 * 상태(알림 생성 + {@code answer_notification_processed_at})로 이어지고, 중복 메시지가 중복 알림을
 * 만들지 않는다는 것이다.
 */
@SpringBootTest
@EnabledIf(value = "shinhan.fibri.ieum.testsupport.DockerAvailability#isAvailable", disabledReason = "Docker unavailable")
class QuestionCompletionRelayIntegrationTest {

	@DynamicPropertySource
	static void configure(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, "ai_completion_relay");
		registry.add("app.ai.dispatch.transport", () -> "rabbitmq");
		registry.add("spring.rabbitmq.host", AiJobRabbitContainer::host);
		registry.add("spring.rabbitmq.port", AiJobRabbitContainer::amqpPort);
		registry.add("spring.rabbitmq.username", AiJobRabbitContainer::username);
		registry.add("spring.rabbitmq.password", AiJobRabbitContainer::password);
	}

	@Autowired
	private JdbcClient jdbc;

	@Autowired
	private RabbitTemplate rabbitTemplate;

	@Test
	void publishedCompletionMessageCreatesTheNotificationAndRecordsTheAckColumn() {
		Fixture fixture = insertCompletedFixture();

		publishCompletedMessage(fixture.questionId(), fixture.answerId());

		waitUntil(() -> notificationCount(fixture.questionId()) == 1, Duration.ofSeconds(15));
		assertThat(notificationCount(fixture.questionId())).isEqualTo(1);
		assertThat(notificationProcessedAt(fixture.questionId())).isNotNull();
	}

	@Test
	void duplicateCompletionMessagesStillProduceExactlyOneNotification() {
		Fixture fixture = insertCompletedFixture();

		publishCompletedMessage(fixture.questionId(), fixture.answerId());
		waitUntil(() -> notificationProcessedAt(fixture.questionId()) != null, Duration.ofSeconds(15));
		// 첫 메시지가 이미 ACK 컬럼을 채운 뒤에도 두 번째(중복) 메시지를 넣는다 — relay 가
		// 안전망으로 재발행했을 때와 같은 상황이다(spec.md §8.5 "ACK 될 때까지 계속 재발행").
		publishCompletedMessage(fixture.questionId(), fixture.answerId());

		// 두 번째 메시지도 정상 소비(ACK)되는지 확인하기 위해 큐가 비워질 때까지 기다린다.
		waitUntil(() -> queueMessageCount() == 0, Duration.ofSeconds(15));

		assertThat(notificationCount(fixture.questionId())).isEqualTo(1);
	}

	private void publishCompletedMessage(long questionId, long answerId) {
		String jobId = UUID.randomUUID().toString();
		String body = """
			{"schemaVersion":%d,"jobId":"%s","jobType":"question_answer_completed",\
			"questionId":%d,"answerId":%d,"occurredAt":"%s"}""".formatted(
			AiJobTopology.SCHEMA_VERSION, jobId, questionId, answerId, Instant.now()
		);
		MessageProperties properties = new MessageProperties();
		properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
		properties.setContentEncoding(StandardCharsets.UTF_8.name());
		properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
		properties.setMessageId(jobId);
		properties.setCorrelationId(jobId);
		properties.setType("question_answer_completed");
		properties.setAppId("ieum-app-ai");
		Message message = new Message(body.getBytes(StandardCharsets.UTF_8), properties);
		rabbitTemplate.send(
			AiJobTopology.EXCHANGE_RESULTS, AiJobTopology.ROUTING_KEY_QUESTION_ANSWER_COMPLETED, message
		);
	}

	private long notificationCount(long questionId) {
		return jdbc.sql("""
			SELECT count(*) FROM notifications
			WHERE event_key = :eventKey
			""")
			.param("eventKey", "ai-answer-created:question:" + questionId)
			.query(Long.class)
			.single();
	}

	private Instant notificationProcessedAt(long questionId) {
		return jdbc.sql("""
			SELECT answer_notification_processed_at FROM ai_question_tasks
			WHERE question_id = :questionId
			""")
			.param("questionId", questionId)
			.query(java.time.OffsetDateTime.class)
			.optional()
			.map(java.time.OffsetDateTime::toInstant)
			.orElse(null);
	}

	private long queueMessageCount() {
		Integer count = rabbitTemplate.execute(channel ->
			channel.queueDeclarePassive(AiJobTopology.QUEUE_QUESTION_ANSWER_COMPLETED).getMessageCount()
		);
		return count == null ? 0 : count;
	}

	private static void waitUntil(BooleanSupplier condition, Duration timeout) {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return;
			}
			try {
				Thread.sleep(200);
			}
			catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private Fixture insertCompletedFixture() {
		String suffix = UUID.randomUUID().toString();
		long userId = jdbc.sql("""
			INSERT INTO users (email, password_hash, nickname, email_verified)
			VALUES (:email, 'hash', :nickname, true)
			RETURNING user_id
			""")
			.param("email", "completion-relay-" + suffix + "@example.com")
			.param("nickname", "relay-" + suffix.substring(0, 8))
			.query(Long.class)
			.single();
		long pinId = jdbc.sql("""
			INSERT INTO pins (author_id, pin_type, location, address)
			VALUES (:userId, 'question', ST_SetSRID(ST_MakePoint(127.0, 37.5), 4326)::geography, 'Seoul')
			RETURNING pin_id
			""")
			.param("userId", userId)
			.query(Long.class)
			.single();
		long questionId = jdbc.sql("""
			INSERT INTO questions (pin_id, author_id, title, content)
			VALUES (:pinId, :userId, 'question title', 'question content')
			RETURNING question_id
			""")
			.param("pinId", pinId)
			.param("userId", userId)
			.query(Long.class)
			.single();
		long answerId = jdbc.sql("""
			INSERT INTO answers (question_id, author_id, is_ai, content)
			VALUES (:questionId, NULL, TRUE, 'AI answer')
			RETURNING answer_id
			""")
			.param("questionId", questionId)
			.query(Long.class)
			.single();
		jdbc.sql("""
			INSERT INTO ai_question_tasks (
				question_id, status, stage, embedding, embedding_model, answer_id, answer_outcome,
				generation_provider, generation_model, grounding_status, evidence, completed_at
			)
			VALUES (
				:questionId, 'completed', 'persisting', array_fill(0.0::real, ARRAY[768])::vector,
				'gemini-embedding-2', :answerId, 'local_grounded', 'test', 'test-model', 'grounded',
				'[{"source":"test"}]'::jsonb, CURRENT_TIMESTAMP
			)
			""")
			.param("questionId", questionId)
			.param("answerId", answerId)
			.update();
		return new Fixture(userId, pinId, questionId, answerId);
	}

	private record Fixture(long userId, long pinId, long questionId, long answerId) {
	}
}
