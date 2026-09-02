package shinhan.fibri.ieum.testsupport;

import org.testcontainers.DockerClientFactory;

/**
 * {@code @EnabledIf} 용 Docker 가용성 게이트. {@code @Testcontainers(disabledWithoutDocker = true)} 는
 * {@code @Container} 필드가 있는 클래스에만 걸리므로, 컨테이너를 정적 홀더로 공유하는 테스트는 이걸 쓴다.
 * app-main 의 동명 클래스를 이 모듈에 그대로 복제한다(별도 Gradle 모듈이라 테스트 소스 공유 불가).
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
