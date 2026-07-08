package shinhan.fibri.ieum.common.auth.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.jdbc.core.JdbcTemplate;
import shinhan.fibri.ieum.common.auth.domain.AuthProvider;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;

@DataJpaTest
class UserRepositoryTest {

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private UserSettingsRepository userSettingsRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Test
	void userRepositoryQueriesIgnoreSoftDeletedUsers() {
		User active = userRepository.save(User.createEmailUser(
				"active@example.com",
				"hash",
				"active",
				LocalDate.of(1990, 1, 1),
				GenderType.female,
				"KR"
		));
		User deleted = User.createEmailUser(
				"deleted@example.com",
				"hash",
				"deleted",
				LocalDate.of(1991, 1, 1),
				GenderType.male,
				"US"
		);
		deleted.markDeleted(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
		deleted = userRepository.save(deleted);

		assertThat(userRepository.existsByEmailAndProviderAndDeletedAtIsNull("active@example.com", AuthProvider.email))
				.isTrue();
		assertThat(userRepository.existsByEmailAndProviderAndDeletedAtIsNull("deleted@example.com", AuthProvider.email))
				.isFalse();
		assertThat(userRepository.existsByNicknameAndDeletedAtIsNull("active")).isTrue();
		assertThat(userRepository.existsByNicknameAndDeletedAtIsNull("deleted")).isFalse();
		assertThat(userRepository.findByEmailAndProviderAndDeletedAtIsNull("active@example.com", AuthProvider.email))
				.contains(active);
		assertThat(userRepository.findByEmailAndProviderAndDeletedAtIsNull("deleted@example.com", AuthProvider.email))
				.isEmpty();
		assertThat(userRepository.findByIdAndDeletedAtIsNull(active.getId())).contains(active);
		assertThat(userRepository.findByIdAndDeletedAtIsNull(deleted.getId())).isEmpty();
	}

	@Test
	void existsByProfileFileIdAndDeletedAtIsNullIgnoresSoftDeletedUsers() {
		UUID profileFileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		User active = User.createEmailUser(
				"profile-active@example.com",
				"hash",
				"profile-active",
				LocalDate.of(1990, 1, 1),
				GenderType.female,
				"KR"
		);
		active.linkProfileImage(profileFileId);
		userRepository.save(active);

		User deleted = User.createEmailUser(
				"profile-deleted@example.com",
				"hash",
				"profile-deleted",
				LocalDate.of(1991, 1, 1),
				GenderType.male,
				"US"
		);
		deleted.linkProfileImage(UUID.fromString("22222222-2222-2222-2222-222222222222"));
		deleted.markDeleted(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
		userRepository.save(deleted);

		assertThat(userRepository.existsByProfileFileIdAndDeletedAtIsNull(profileFileId)).isTrue();
		assertThat(userRepository.existsByProfileFileIdAndDeletedAtIsNull(deleted.getProfileFileId())).isFalse();
	}

	@Test
	void findsActiveSocialUserByProviderAndProviderUid() {
		User active = userRepository.save(User.createSocialUser(
				AuthProvider.google,
				"google-sub-123",
				"social@example.com",
				true,
				"hash",
				"social",
				LocalDate.of(1990, 1, 1),
				GenderType.female,
				"KR"
		));
		User deleted = User.createSocialUser(
				AuthProvider.google,
				"deleted-sub-123",
				"deleted-social@example.com",
				true,
				"hash",
				"deleted-social",
				LocalDate.of(1991, 1, 1),
				GenderType.male,
				"US"
		);
		deleted.markDeleted(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
		deleted = userRepository.save(deleted);

		assertThat(userRepository.findByProviderAndProviderUidAndDeletedAtIsNull(AuthProvider.google, "google-sub-123"))
				.contains(active);
		assertThat(userRepository.findByProviderAndProviderUidAndDeletedAtIsNull(AuthProvider.google, "deleted-sub-123"))
				.isEmpty();
		assertThat(userRepository.findByProviderAndProviderUidAndDeletedAtIsNull(AuthProvider.kakao, "google-sub-123"))
				.isEmpty();
		assertThat(deleted.getDeletedAt()).isNotNull();
	}

	@Test
	void inheritedUserRepositoryQueriesIgnoreSoftDeletedUsers() {
		User deleted = User.createEmailUser(
				"inherited-deleted@example.com",
				"hash",
				"inherited-deleted",
				LocalDate.of(1991, 1, 1),
				GenderType.male,
				"US"
		);
		deleted.markDeleted(OffsetDateTime.parse("2026-01-01T00:00:00Z"));
		deleted = userRepository.saveAndFlush(deleted);
		entityManager.clear();

		assertThat(userRepository.findById(deleted.getId())).isEmpty();
		assertThat(userRepository.findAll()).doesNotContain(deleted);
	}

	@Test
	void userSettingsRepositoryPersistsDefaultSettingsForUser() {
		User user = userRepository.save(User.createEmailUser(
				"settings@example.com",
				"hash",
				"settings",
				LocalDate.of(1992, 2, 2),
				GenderType.female,
				"KR"
		));

		UserSettings settings = userSettingsRepository.save(UserSettings.forSignup(user, "ko"));

		assertThat(settings.getId()).isNotNull();
		assertThat(settings.getUser()).isEqualTo(user);
		assertThat(userSettingsRepository.findById(settings.getId())).contains(settings);
	}

	@Test
	void userEntityMatchesProductionColumnNamesAndLowercaseEnumValues() {
		User user = userRepository.saveAndFlush(User.createEmailUser(
				"schema@example.com",
				"hash",
				"schema",
				LocalDate.of(1993, 3, 3),
				GenderType.other,
				"JP"
		));

		String provider = jdbcTemplate.queryForObject(
				"select provider from users where user_id = ?",
				String.class,
				user.getId()
		);
		String role = jdbcTemplate.queryForObject(
				"select role from users where user_id = ?",
				String.class,
				user.getId()
		);
		String status = jdbcTemplate.queryForObject(
				"select status from users where user_id = ?",
				String.class,
				user.getId()
		);
		String grade = jdbcTemplate.queryForObject(
				"select grade from users where user_id = ?",
				String.class,
				user.getId()
		);
		String gender = jdbcTemplate.queryForObject(
				"select gender from users where user_id = ?",
				String.class,
				user.getId()
		);
		String nationality = jdbcTemplate.queryForObject(
				"select nationality from users where user_id = ?",
				String.class,
				user.getId()
		);
		String providerUid = jdbcTemplate.queryForObject(
				"select provider_uid from users where user_id = ?",
				String.class,
				user.getId()
		);

		assertThat(provider).isEqualTo("email");
		assertThat(role).isEqualTo("user");
		assertThat(status).isEqualTo("active");
		assertThat(grade).isEqualTo("bronze");
		assertThat(gender).isEqualTo("other");
		assertThat(nationality).isEqualTo("JP");
		assertThat(providerUid).isNull();
	}

	@SpringBootApplication(scanBasePackages = "shinhan.fibri.ieum.common")
	@EntityScan(basePackageClasses = User.class)
	static class TestApplication {
	}
}
