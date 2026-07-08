package shinhan.fibri.ieum.common.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserTest {

	@Test
	void createEmailUserAppliesSignupDefaultsAndNormalizesEmail() {
		User user = User.createEmailUser(
				"  Member@Example.COM  ",
				"$2a$10$passwordHash",
				"ieum",
				LocalDate.of(1995, 5, 20),
				GenderType.female,
				"KR"
		);

		assertThat(user.getEmail()).isEqualTo("member@example.com");
		assertThat(user.getPasswordHash()).isEqualTo("$2a$10$passwordHash");
		assertThat(user.getNickname()).isEqualTo("ieum");
		assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(1995, 5, 20));
		assertThat(user.getGender()).isEqualTo(GenderType.female);
		assertThat(user.getNationality()).isEqualTo("KR");
		assertThat(user.getProvider()).isEqualTo(AuthProvider.email);
		assertThat(user.isEmailVerified()).isTrue();
		assertThat(user.getRole()).isEqualTo(UserRole.user);
		assertThat(user.getStatus()).isEqualTo(UserStatus.active);
		assertThat(user.getGrade()).isEqualTo(UserGrade.bronze);
		assertThat(user.getAcceptedCount()).isZero();
		assertThat(user.isPasswordResetRequired()).isFalse();
		assertThat(user.getDeletedAt()).isNull();
	}

	@Test
	void createSocialUserAppliesSignupDefaultsWithProviderIdentity() {
		User user = User.createSocialUser(
				AuthProvider.google,
				"google-sub-123",
				"  Social@Example.COM  ",
				true,
				"$2a$10$randomPasswordHash",
				"social",
				LocalDate.of(1998, 8, 8),
				GenderType.other,
				"KR"
		);

		assertThat(user.getProvider()).isEqualTo(AuthProvider.google);
		assertThat(user.getProviderUid()).isEqualTo("google-sub-123");
		assertThat(user.getEmail()).isEqualTo("social@example.com");
		assertThat(user.isEmailVerified()).isTrue();
		assertThat(user.getPasswordHash()).isEqualTo("$2a$10$randomPasswordHash");
		assertThat(user.getNickname()).isEqualTo("social");
		assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(1998, 8, 8));
		assertThat(user.getGender()).isEqualTo(GenderType.other);
		assertThat(user.getNationality()).isEqualTo("KR");
		assertThat(user.getRole()).isEqualTo(UserRole.user);
		assertThat(user.getStatus()).isEqualTo(UserStatus.active);
		assertThat(user.getGrade()).isEqualTo(UserGrade.bronze);
		assertThat(user.getAcceptedCount()).isZero();
		assertThat(user.isPasswordResetRequired()).isFalse();
		assertThat(user.getDeletedAt()).isNull();
	}

	@Test
	void createSocialUserRejectsEmailProvider() {
		assertThatThrownBy(() -> User.createSocialUser(
				AuthProvider.email,
				"email-sub-123",
				"social@example.com",
				true,
				"$2a$10$randomPasswordHash",
				"social",
				LocalDate.of(1998, 8, 8),
				GenderType.other,
				"KR"
		)).isInstanceOf(IllegalArgumentException.class)
				.hasMessage("social provider must not be email");
	}

	@Test
	void updateProfileChangesEditableBasicInfoOnly() {
		User user = User.createEmailUser(
				"user@example.com",
				"hash",
				"before",
				LocalDate.of(1995, 5, 20),
				GenderType.female,
				"KR"
		);

		user.updateProfile(
				"after",
				LocalDate.of(1996, 6, 21),
				GenderType.male,
				"US"
		);

		assertThat(user.getNickname()).isEqualTo("after");
		assertThat(user.getBirthDate()).isEqualTo(LocalDate.of(1996, 6, 21));
		assertThat(user.getGender()).isEqualTo(GenderType.male);
		assertThat(user.getNationality()).isEqualTo("US");
		assertThat(user.getEmail()).isEqualTo("user@example.com");
		assertThat(user.getGrade()).isEqualTo(UserGrade.bronze);
		assertThat(user.getAcceptedCount()).isZero();
	}

	@Test
	void linkAndClearProfileImageUpdatesProfileFileIdOnly() {
		User user = User.createEmailUser(
				"user@example.com",
				"hash",
				"nickname",
				LocalDate.of(1995, 5, 20),
				GenderType.female,
				"KR"
		);
		UUID profileFileId = UUID.fromString("55555555-5555-5555-5555-555555555555");

		user.linkProfileImage(profileFileId);

		assertThat(user.getProfileFileId()).isEqualTo(profileFileId);
		assertThat(user.getEmail()).isEqualTo("user@example.com");
		assertThat(user.getNickname()).isEqualTo("nickname");

		user.clearProfileImage();

		assertThat(user.getProfileFileId()).isNull();
	}

}
