package shinhan.fibri.ieum.main.user.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Pattern;
import java.util.Set;
import shinhan.fibri.ieum.common.auth.validation.AuthValidationRules;

public record UpdateUserSettingsRequest(
	@Pattern(regexp = AuthValidationRules.SUPPORTED_LANGUAGE_PATTERN, message = "Language is not supported")
	String language,

	Boolean cameraPermission,

	Boolean pushPermission,

	@JsonProperty("notifyAll")
	Boolean notifyAllEnabled,

	Boolean notifyMeeting,

	Boolean notifyQuestion,

	Integer notifyRadiusKm
) {
	private static final Set<Integer> SUPPORTED_RADIUS_KM = Set.of(3, 5, 10);

	public boolean hasUnsupportedRadius() {
		return notifyRadiusKm != null && !SUPPORTED_RADIUS_KM.contains(notifyRadiusKm);
	}
}
