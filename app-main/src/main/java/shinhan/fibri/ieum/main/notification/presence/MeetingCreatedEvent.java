package shinhan.fibri.ieum.main.notification.presence;

public record MeetingCreatedEvent(Long meetingId, Long hostId, String title, double latitude, double longitude) {
}
