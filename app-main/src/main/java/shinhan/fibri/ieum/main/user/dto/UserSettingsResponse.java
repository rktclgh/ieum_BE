package shinhan.fibri.ieum.main.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;

public record UserSettingsResponse(
	String language,
	boolean cameraPermission,
	boolean pushPermission,
	@JsonProperty("notifyAll")
	boolean notifyAllEnabled,
	boolean notifyMeeting,
	boolean notifyQuestion,
	int notifyRadiusKm
) {
	public static UserSettingsResponse from(UserSettings settings) {
		return new UserSettingsResponse(
			settings.getLanguage(),
			settings.isCameraPermission(),
			settings.isPushPermission(),
			settings.isNotifyAll(),
			settings.isNotifyMeeting(),
			settings.isNotifyQuestion(),
			settings.getNotifyRadiusKm()
		);
	}
}
