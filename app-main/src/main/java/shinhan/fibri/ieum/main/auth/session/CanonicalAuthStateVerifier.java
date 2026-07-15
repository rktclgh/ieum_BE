package shinhan.fibri.ieum.main.auth.session;

import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.UserAuthState;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;

@Component
@RequiredArgsConstructor
public class CanonicalAuthStateVerifier {

	private final UserRepository userRepository;

	public Optional<UserAuthState> findActiveMatching(AuthSession session) {
		return userRepository.findAuthStateById(session.userId())
			.filter(canonical -> canonical.status() == UserStatus.active)
			.filter(canonical -> canonical.email().equals(session.email()))
			.filter(canonical -> canonical.role() == session.role())
			.filter(canonical -> canonical.status() == session.status())
			.filter(canonical -> canonical.authVersion() == session.authVersion());
	}
}
