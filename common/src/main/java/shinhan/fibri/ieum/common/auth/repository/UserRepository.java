package shinhan.fibri.ieum.common.auth.repository;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.User;

public interface UserRepository extends JpaRepository<User, Long> {

	boolean existsByEmailAndProviderAndDeletedAtIsNull(String email, AuthProvider provider);

	boolean existsByNicknameAndDeletedAtIsNull(String nickname);

	Optional<User> findByEmailAndProviderAndDeletedAtIsNull(String email, AuthProvider provider);

	Optional<User> findByIdAndDeletedAtIsNull(Long userId);
}
