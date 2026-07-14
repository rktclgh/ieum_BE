package shinhan.fibri.ieum.main.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import shinhan.fibri.ieum.main.auth.domain.LoginLog;

public interface LoginLogRepository extends JpaRepository<LoginLog, Long> {
}
