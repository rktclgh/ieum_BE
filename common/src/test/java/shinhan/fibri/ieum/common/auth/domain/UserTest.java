package shinhan.fibri.ieum.common.auth.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
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

}
