package shinhan.fibri.ieum.main.auth.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.main.auth.dto.SignupRequest;
import shinhan.fibri.ieum.main.auth.dto.SignupResponse;
import shinhan.fibri.ieum.main.auth.exception.EmailTakenException;
import shinhan.fibri.ieum.main.auth.exception.InvalidEmailVerificationTokenException;
import shinhan.fibri.ieum.main.auth.exception.NicknameTakenException;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SignupService {

	private final EmailVerificationCodeStore codeStore;
	private final UserRepository userRepository;
	private final UserSettingsRepository userSettingsRepository;
	private final PasswordHasher passwordHasher;

	@Transactional
	public SignupResponse signup(SignupRequest request) {
		String email = normalizeEmail(request.email());
		String verifiedEmail = codeStore.findSignupVerificationEmail(request.emailVerificationToken())
			.orElseThrow(InvalidEmailVerificationTokenException::new);
		if (!verifiedEmail.equals(email)) {
			throw new InvalidEmailVerificationTokenException();
		}
		if (userRepository.existsByEmailAndProviderAndDeletedAtIsNull(email, AuthProvider.email)) {
			throw new EmailTakenException();
		}
		if (userRepository.existsByNicknameAndDeletedAtIsNull(request.nickname())) {
			throw new NicknameTakenException();
		}

		User user = User.createEmailUser(
			email,
			passwordHasher.hash(request.password()),
			request.nickname(),
			request.birthDate()
		);
		User savedUser = userRepository.save(user);
		userSettingsRepository.save(UserSettings.defaultFor(savedUser));
		deleteVerificationTokenAfterCommit(request.emailVerificationToken());
		return new SignupResponse(savedUser.getId());
	}

	private String normalizeEmail(String email) {
		return email.trim().toLowerCase(Locale.ROOT);
	}

	private void deleteVerificationTokenAfterCommit(String token) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			codeStore.deleteSignupVerificationToken(token);
			return;
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				codeStore.deleteSignupVerificationToken(token);
			}
		});
	}
}
