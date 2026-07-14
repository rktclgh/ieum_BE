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
	String profileImageUrl,
	UserSettingsResponse settings
) {
	public static UserMeResponse of(User user, UserSettings settings) {
		return new UserMeResponse(
			user.getId(),
			user.getEmail(),
			user.getNickname(),
			user.getBirthDate(),
			user.getGender() == null ? null : user.getGender().name(),
			user.getNationality(),
			user.getGrade().name(),
			user.getAcceptedCount(),
			profileImageUrl(user),
			UserSettingsResponse.from(settings)
		);
	}

	private static String profileImageUrl(User user) {
		if (user.getProfileFileId() == null) {
			return null;
		}
		return "/api/v1/files/" + user.getProfileFileId();
	}
}
