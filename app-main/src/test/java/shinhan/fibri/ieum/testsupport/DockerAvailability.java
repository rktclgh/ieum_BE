package shinhan.fibri.ieum.testsupport;

import org.testcontainers.DockerClientFactory;

/**
 * {@code @EnabledIf} 용 Docker 가용성 게이트. {@code @Testcontainers(disabledWithoutDocker = true)} 는
 * {@code @Container} 필드가 있는 클래스에만 걸리므로, 컨테이너를 정적 홀더로 공유하는 테스트는 이걸 쓴다.
 */
public final class DockerAvailability {

	private DockerAvailability() {
	}

	public static boolean isAvailable() {
		try {
			return DockerClientFactory.instance().isDockerAvailable();
		}
		catch (RuntimeException unavailable) {
			return false;
		}
	}
}
