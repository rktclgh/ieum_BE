package shinhan.fibri.ieum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * EC2-1 배포 진입점: 핵심 REST API + WebSocket + SSE.
 *
 * <p>루트 패키지(shinhan.fibri.ieum)에 두었으므로 컴포넌트 스캔·엔티티 스캔·
 * JPA Repository 스캔이 기본값으로 common 모듈까지 함께 커버한다.
 */
@SpringBootApplication
public class MainApplication {

	public static void main(String[] args) {
		SpringApplication.run(MainApplication.class, args);
	}

}
