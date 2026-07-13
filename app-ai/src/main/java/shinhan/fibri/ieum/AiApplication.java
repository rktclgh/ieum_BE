package shinhan.fibri.ieum;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * EC2-2 배포 진입점: AI 전용 서버.
 *
 * <p>루트 패키지(shinhan.fibri.ieum)에 두었으므로 컴포넌트 스캔·엔티티 스캔·
 * JPA Repository 스캔이 기본값으로 common 모듈까지 함께 커버한다.
 */
@SpringBootApplication
@EnableScheduling
public class AiApplication {

	public static void main(String[] args) {
		SpringApplication.run(AiApplication.class, args);
	}

}
