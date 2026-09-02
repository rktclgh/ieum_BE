package shinhan.fibri.ieum.testsupport;

import org.testcontainers.rabbitmq.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * AI job 토폴로지 테스트용 RabbitMQ 브로커. 클래스 로딩 시 한 번 뜨고 JVM 종료까지 재사용한다
 * (Ryuk 이 회수한다). app-main 의 동명 클래스({@code app-main/src/test/.../testsupport/AiJobRabbitContainer})와
 * 같은 패턴을 이 모듈에 그대로 복제한다 — 두 모듈은 별도 Gradle 모듈이라 테스트 소스를 공유하지 않는다.
 * 브로커 vhost 는 배포 설정일 뿐 {@code AiJobTopology} 상수가 아니므로 여기서는 기본 vhost {@code /}를 쓴다.
 */
public final class AiJobRabbitContainer {

	private static final DockerImageName IMAGE = DockerImageName.parse("rabbitmq:4.1-alpine");
	private static final RabbitMQContainer RABBIT = start();

	private AiJobRabbitContainer() {
	}

	public static String host() {
		return RABBIT.getHost();
	}

	public static int amqpPort() {
		return RABBIT.getAmqpPort();
	}

	public static String username() {
		return RABBIT.getAdminUsername();
	}

	public static String password() {
		return RABBIT.getAdminPassword();
	}

	private static RabbitMQContainer start() {
		RabbitMQContainer container = new RabbitMQContainer(IMAGE);
		container.start();
		return container;
	}
}
