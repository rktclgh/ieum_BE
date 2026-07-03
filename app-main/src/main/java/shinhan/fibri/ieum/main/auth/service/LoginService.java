package shinhan.fibri.ieum.main.auth.service;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.auth.validation.AuthEmailNormalizer;
import shinhan.fibri.ieum.main.auth.domain.LoginLog;
import shinhan.fibri.ieum.main.auth.dto.LoginRequest;
import shinhan.fibri.ieum.main.auth.dto.LoginResponse;
import shinhan.fibri.ieum.main.auth.exception.EmailNotVerifiedException;
import shinhan.fibri.ieum.main.auth.exception.InvalidCredentialsException;
import shinhan.fibri.ieum.main.auth.exception.SuspendedUserException;
import shinhan.fibri.ieum.main.auth.repository.LoginLogRepository;
import shinhan.fibri.ieum.main.auth.session.AccessTokenIssuer;
import shinhan.fibri.ieum.main.auth.session.AuthSession;
import shinhan.fibri.ieum.main.auth.session.OpaqueTokenGenerator;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.main.auth.session.Sha256TokenHasher;

@Service
@RequiredArgsConstructor
public class LoginService {

	private static final String DUMMY_PASSWORD_HASH =
		"$2a$10$N9qo8uLOickgx2ZMRZoMye.IjZAgcfl7p92ldGxad68LJZdL17lhWy";

	private final UserRepository userRepository;
	private final PasswordHasher passwordHasher;
	private final LoginLogRepository loginLogRepository;
	private final OpaqueTokenGenerator tokenGenerator;
	private final Sha256TokenHasher tokenHasher;
	private final AccessTokenIssuer accessTokenIssuer;
	private final RedisAuthSessionStore sessionStore;

	@Transactional
	public LoginResult login(LoginRequest request) {
		String email = AuthEmailNormalizer.normalize(request.email());
		User user = userRepository.findByEmailAndProviderAndDeletedAtIsNull(email, AuthProvider.email)
			.orElse(null);
		if (user == null) {
			passwordHasher.matches(request.password(), DUMMY_PASSWORD_HASH);
			throw new InvalidCredentialsException();
		}
		if (!passwordHasher.matches(request.password(), user.getPasswordHash())) {
			throw new InvalidCredentialsException();
		}
		if (!user.isEmailVerified()) {
			throw new EmailNotVerifiedException();
		}
		if (user.getStatus() == UserStatus.suspended) {
			throw new SuspendedUserException();
		}

		loginLogRepository.save(LoginLog.emailLogin(user));

		String sessionId = tokenGenerator.generate();
		String refreshToken = tokenGenerator.generate();
		String csrfToken = tokenGenerator.generate();
		String refreshTokenHash = tokenHasher.hash(refreshToken);
		String accessToken = accessTokenIssuer.issue(user.getId(), sessionId, user.getRole());
		AuthSession session = new AuthSession(
			sessionId,
			user.getId(),
			user.getEmail(),
			refreshTokenHash,
			null,
			user.getRole(),
			user.getStatus(),
			OffsetDateTime.now()
		);
		writeSessionAfterCommit(session);

		return new LoginResult(
			new LoginResponse(user.getId(), user.getRole(), user.isPasswordResetRequired()),
			accessToken,
			refreshToken,
			csrfToken
		);
	}

	private void writeSessionAfterCommit(AuthSession session) {
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					sessionStore.create(session);
				}
			});
			return;
		}
		sessionStore.create(session);
	}
}
