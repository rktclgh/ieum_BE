package shinhan.fibri.ieum.main.notification.presence;

public record PresenceSnapshot(
	Double latitude,
	Double longitude,
	boolean notifyAllEnabled,
	boolean notifyQuestion,
	boolean notifyMeeting,
	int notifyRadiusKm
) {
}
