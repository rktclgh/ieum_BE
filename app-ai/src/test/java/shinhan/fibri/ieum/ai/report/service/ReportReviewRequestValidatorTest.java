package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewImage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewMessage;
import shinhan.fibri.ieum.common.ai.report.dto.ReportReviewRequest;

class ReportReviewRequestValidatorTest {

	private final ReportReviewRequestValidator validator = new ReportReviewRequestValidator();

	@Test
	void acceptsAnAliasedBoundedReviewRequest() {
		ReportReviewRequest request = request(900L, 2L, List.of(
			message(1L, "other_actor_1", "context"),
			message(2L, "reported_user", "reported content"),
			message(3L, "reported_user", "later context")
		));

		assertThatCode(() -> validator.validate(900L, request)).doesNotThrowAnyException();
	}

	@Test
	void rejectsAPathAndBodyReportIdMismatch() {
		ReportReviewRequest request = request(901L, 2L, List.of(message(2L, "reported_user", "reported content")));

		assertThatThrownBy(() -> validator.validate(900L, request))
			.isInstanceOf(InvalidReportReviewRequestException.class)
			.hasMessageContaining("reportId");
	}

	@Test
	void rejectsANullRequestAsAnInvalidReviewRequest() {
		assertThatThrownBy(() -> validator.validate(900L, null))
			.isInstanceOf(InvalidReportReviewRequestException.class)
			.hasMessageContaining("request");
	}

	@Test
	void rejectsWhenTheReportedMessageIsNotAttributedToTheReportedUser() {
		ReportReviewRequest request = request(900L, 2L, List.of(message(2L, "other_actor_1", "reported content")));

		assertThatThrownBy(() -> validator.validate(900L, request))
			.isInstanceOf(InvalidReportReviewRequestException.class)
			.hasMessageContaining("reported_user");
	}

	@Test
	void acceptsTheReporterAliasInContext() {
		ReportReviewRequest request = request(900L, 2L, List.of(
			message(1L, "reporter", "report context"),
			message(2L, "reported_user", "reported content")
		));

		assertThatCode(() -> validator.validate(900L, request)).doesNotThrowAnyException();
	}

	@Test
	void rejectsDuplicateContextMessageIds() {
		ReportReviewRequest request = request(900L, 2L, List.of(
			message(2L, "reported_user", "reported content"),
			message(2L, "other_actor_1", "ambiguous duplicate")
		));

		assertThatThrownBy(() -> validator.validate(900L, request))
			.isInstanceOf(InvalidReportReviewRequestException.class)
			.hasMessageContaining("messageId");
	}

	@Test
	void rejectsAnImageWithoutUsableMetadata() {
		ReportReviewMessage imageOnly = new ReportReviewMessage(
			2L, "reported_user", null, new ReportReviewImage(null, null), "2026-07-11T10:01:00Z"
		);
		ReportReviewRequest request = request(900L, 2L, List.of(imageOnly));

		assertThatThrownBy(() -> validator.validate(900L, request))
			.isInstanceOf(InvalidReportReviewRequestException.class)
			.hasMessageContaining("image");
	}

	private ReportReviewRequest request(long reportId, long reportedMessageId, List<ReportReviewMessage> messages) {
		return new ReportReviewRequest(
			reportId,
			UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
			reportedMessageId,
			"harassment",
			"detail",
			"a".repeat(64),
			messages
		);
	}

	private ReportReviewMessage message(long messageId, String actor, String content) {
		return new ReportReviewMessage(messageId, actor, content, null, "2026-07-11T10:01:00Z");
	}
}
