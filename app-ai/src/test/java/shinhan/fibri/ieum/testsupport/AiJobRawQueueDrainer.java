package shinhan.fibri.ieum.testsupport;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;
import shinhan.fibri.ieum.common.ai.job.AiJobTopology;

/**
 * 테스트 전용 최소 컨슈머 루프. {@code @RabbitListener} 컨테이너 배선(Spring Boot 자동설정) 없이도
 * manual ACK 계약(spec.md §6.4)을 그대로 지키며 실제 브로커 채널로 핸들러를 구동한다.
 *
 * <p>{@code QuestionAnswerDispatchEndToEndTest.SimpleQueueDrainer}(Task 5)와 같은 패턴을 재시도/DLQ
 * 통합 테스트 2종({@code AiJobRetryDlqIntegrationTest}, {@code AiJobImmediateDlqIntegrationTest})이
 * 공유할 수 있도록 일반화했다.
 */
public final class AiJobRawQueueDrainer {

	@FunctionalInterface
	public interface Handler {
		void handle(Channel channel, Delivery delivery) throws Exception;
	}

	private final Connection connection;
	private final Channel channel;

	private AiJobRawQueueDrainer(Connection connection, Channel channel) {
		this.connection = connection;
		this.channel = channel;
	}

	/** manual ACK — 핸들러가 직접 {@code basicAck}/{@code basicNack}을 호출해야 한다(호출하지 않으면
	 * 메시지는 delivered-but-unacked 상태로 큐의 메시지 수에 계속 잡힌다 — DLQ 도착 관찰용으로 유용). */
	public static AiJobRawQueueDrainer consumingWith(String queue, Handler handler) throws Exception {
		return start(queue, false, handler);
	}

	private static AiJobRawQueueDrainer start(String queue, boolean autoAck, Handler handler) throws Exception {
		ConnectionFactory rawFactory = new ConnectionFactory();
		rawFactory.setHost(AiJobRabbitContainer.host());
		rawFactory.setPort(AiJobRabbitContainer.amqpPort());
		rawFactory.setUsername(AiJobRabbitContainer.username());
		rawFactory.setPassword(AiJobRabbitContainer.password());
		Connection connection = rawFactory.newConnection();
		Channel channel = connection.createChannel();
		channel.basicQos(AiJobTopology.PREFETCH);
		channel.basicConsume(queue, autoAck, (consumerTag, delivery) -> {
			try {
				handler.handle(channel, delivery);
			}
			catch (Exception exception) {
				throw new RuntimeException(exception);
			}
		}, consumerTag -> { });
		return new AiJobRawQueueDrainer(connection, channel);
	}

	public void stop() throws Exception {
		channel.close();
		connection.close();
	}
}
