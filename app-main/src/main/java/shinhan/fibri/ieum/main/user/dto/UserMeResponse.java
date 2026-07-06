package shinhan.fibri.ieum.main.user.dto;

import java.time.LocalDate;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;

public record UserMeResponse(
	Long userId,
	String email,
	String nickname,
	LocalDate birthDate,
	String gender,
	String nationality,
	String grade,
	int acceptedCount,
	UserSettingsResponse settings
) {
	public static UserMeResponse of(User user, UserSettings settings) {
		return new UserMeResponse(
			user.getId(),
			user.getEmail(),
			user.getNickname(),
			user.getBirthDate(),
			user.getGender().name(),
			user.getNationality(),
			user.getGrade().name(),
			user.getAcceptedCount(),
			UserSettingsResponse.from(settings)
		);
	}
}
