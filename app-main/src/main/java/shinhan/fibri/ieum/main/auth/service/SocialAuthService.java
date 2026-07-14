package shinhan.fibri.ieum.main.auth.service;

import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.repository.CountryRepository;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.common.auth.repository.UserSettingsRepository;
import shinhan.fibri.ieum.common.auth.validation.AuthValidationRules;
import shinhan.fibri.ieum.main.auth.domain.LoginLog;
import shinhan.fibri.ieum.main.auth.dto.SocialAuthRequest;
import shinhan.fibri.ieum.main.auth.dto.SocialAuthResponse;
import shinhan.fibri.ieum.main.auth.dto.SocialSignupRequest;
import shinhan.fibri.ieum.main.auth.dto.SocialSignupResponse;
import shinhan.fibri.ieum.main.auth.exception.InvalidSignupFieldException;
import shinhan.fibri.ieum.main.auth.exception.InvalidSocialSignupTokenException;
import shinhan.fibri.ieum.main.auth.exception.NicknameTakenException;
import shinhan.fibri.ieum.main.auth.exception.SocialAlreadyRegisteredException;
import shinhan.fibri.ieum.main.auth.exception.SuspendedUserException;
import shinhan.fibri.ieum.main.auth.repository.LoginLogRepository;
import shinhan.fibri.ieum.main.auth.session.IssuedAuthSession;
import shinhan.fibri.ieum.main.auth.session.OpaqueTokenGenerator;
import shinhan.fibri.ieum.main.auth.session.SessionIssuer;

@Service
@RequiredArgsConstructor
public class SocialAuthService {

	private static final Logger log = LoggerFactory.getLogger(SocialAuthService.class);
	private static final Duration SIGNUP_TOKEN_TTL = Duration.ofMinutes(30);
	private static final int SIGNUP_TOKEN_TTL_SECONDS = 1800;

	private final SocialIdentityVerifier identityVerifier;
	private final UserRepository userRepository;
	private final LoginLogRepository loginLogRepository;
	private final SessionIssuer sessionIssuer;
	private final SocialSignupTokenStore signupTokenStore;
	private final OpaqueTokenGenerator tokenGenerator;
	private final UserSettingsRepository userSettingsRepository;
	private final CountryRepository countryRepository;
	private final PasswordHasher passwordHasher;

	@Transactional
	public SocialAuthResult start(SocialAuthRequest request) {
		VerifiedSocialIdentity identity = identityVerifier.verify(request);
		return userRepository
			.findByProviderAndProviderUidAndDeletedAtIsNull(identity.provider(), identity.providerUid())
			.map(user -> loginExistingUser(user, identity))
			.orElseGet(() -> issueSignupToken(identity));
	}

	@Transactional
	public SocialSignupResult signup(SocialSignupRequest request) {
		SocialSignupIdentity identity = signupTokenStore.find(request.socialSignupToken())
			.orElseThrow(InvalidSocialSignupTokenException::new);
		if (userRepository.existsByNicknameAndDeletedAtIsNull(request.nickname())) {
			throw new NicknameTakenException();
		}
		GenderType gender = validateSignupProfile(request);
		String passwordHash = passwordHasher.hash(tokenGenerator.generate());
		User user = User.createSocialUser(
			identity.provider(),
			identity.providerUid(),
			identity.email(),
			identity.emailVerified(),
			passwordHash,
			request.nickname(),
			request.birthDate(),
			gender,
			request.nationality()
		);
		User savedUser = saveUserOrThrowDuplicateException(user);
		userSettingsRepository.save(UserSettings.forSignup(savedUser, request.language()));
		loginLogRepository.save(LoginLog.socialLogin(savedUser, identity.provider()));
		IssuedAuthSession issuedSession = sessionIssuer.issue(savedUser);
		deleteSignupTokenAfterCommit(request.socialSignupToken());
		log.info(
			"Social signup success: userId={} provider={} nickname={}",
			savedUser.getId(),
			identity.provider(),
			request.nickname()
		);
		return new SocialSignupResult(
			new SocialSignupResponse(savedUser.getId(), savedUser.getRole()),
			issuedSession.accessToken(),
			issuedSession.refreshToken(),
			issuedSession.csrfToken()
		);
	}

	private SocialAuthResult loginExistingUser(User user, VerifiedSocialIdentity identity) {
		if (user.getStatus() == UserStatus.suspended) {
			log.warn("Social login blocked (suspended): userId={} provider={}", user.getId(), identity.provider());
			throw new SuspendedUserException();
		}
		loginLogRepository.save(LoginLog.socialLogin(user, identity.provider()));
		IssuedAuthSession issuedSession = sessionIssuer.issue(user);
		log.info("Social login success: userId={} provider={}", user.getId(), identity.provider());
		return new SocialAuthResult(
			SocialAuthResponse.existingUser(user.getId(), user.getRole()),
			issuedSession.accessToken(),
			issuedSession.refreshToken(),
			issuedSession.csrfToken()
		);
	}

	private SocialAuthResult issueSignupToken(VerifiedSocialIdentity identity) {
		String signupToken = tokenGenerator.generate();
		signupTokenStore.save(
			signupToken,
			new SocialSignupIdentity(
				identity.provider(),
				identity.providerUid(),
				identity.email(),
				identity.emailVerified()
			),
			SIGNUP_TOKEN_TTL
		);
		log.info("Social signup token issued (new user): provider={} email={}", identity.provider(), identity.email());
		return new SocialAuthResult(
			SocialAuthResponse.newUser(signupToken, SIGNUP_TOKEN_TTL_SECONDS),
			null,
			null,
			null
		);
	}

	private GenderType validateSignupProfile(SocialSignupRequest request) {
		GenderType gender = parseGender(request.gender());
		if (!countryRepository.existsByCodeAndIsActiveTrue(request.nationality())) {
			throw new InvalidSignupFieldException("nationality", "Nationality is not supported");
		}
		if (!AuthValidationRules.SUPPORTED_LANGUAGES.contains(request.language())) {
			throw new InvalidSignupFieldException("language", "Language is not supported");
		}
		return gender;
	}

	private GenderType parseGender(String gender) {
		if (gender == null) {
			throw new InvalidSignupFieldException("gender", "Gender is not supported");
		}
		try {
			return GenderType.valueOf(gender);
		} catch (IllegalArgumentException exception) {
			throw new InvalidSignupFieldException("gender", "Gender is not supported");
		}
	}

	private User saveUserOrThrowDuplicateException(User user) {
		try {
			return userRepository.save(user);
		} catch (DataIntegrityViolationException exception) {
			throw mapDuplicateConstraint(exception);
		}
	}

	private RuntimeException mapDuplicateConstraint(DataIntegrityViolationException exception) {
		String constraintName = constraintName(exception);
		if (containsConstraint(constraintName, "uidx_users_nickname")) {
			return new NicknameTakenException();
		}
		if (containsConstraint(constraintName, "uidx_users_provider_uid")) {
			return new SocialAlreadyRegisteredException();
		}
		return exception;
	}

	private String constraintName(DataIntegrityViolationException exception) {
		if (exception.getCause() instanceof ConstraintViolationException constraintViolation) {
			return constraintViolation.getConstraintName();
		}
		return String.valueOf(exception.getMostSpecificCause().getMessage()).toLowerCase();
	}

	private boolean containsConstraint(String constraintName, String expectedConstraintName) {
		return constraintName != null && constraintName.contains(expectedConstraintName);
	}

	private void deleteSignupTokenAfterCommit(String token) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			signupTokenStore.delete(token);
			return;
		}

		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				signupTokenStore.delete(token);
			}
		});
	}
}
