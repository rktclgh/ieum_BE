package shinhan.fibri.ieum.main.admin.user.dto;

public record AdminUserActivity(
	int questionCount,
	int answerCount,
	int acceptedCount,
	int reportedCount
) {
}
