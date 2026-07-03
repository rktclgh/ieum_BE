package shinhan.fibri.ieum.common.auth.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {
}
