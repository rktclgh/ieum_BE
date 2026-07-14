package shinhan.fibri.ieum.main.notification.presence;

public record QuestionCreatedEvent(Long questionId, Long authorId, String title, double latitude, double longitude) {
}
