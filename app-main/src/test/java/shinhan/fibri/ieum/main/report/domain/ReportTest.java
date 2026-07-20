package shinhan.fibri.ieum.main.report.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.main.answer.domain.Answer;
import shinhan.fibri.ieum.main.meeting.domain.MeetingSchedule;

class ReportTest {

	private static final ReportContextSnapshot SNAPSHOT = new ReportContextSnapshot("{}", "a".repeat(64));

	@Test
	void messageReportKeepsExistingTargetAndPendingAiWork() {
		User reporter = user(42L, "reporter");
		User sender = user(77L, "sender");
		Message message = Message.text(
			ChatRoom.direct(42L, 77L),
			sender,
			"message",
			OffsetDateTime.parse("2026-07-14T10:00:00+09:00")
		);

		Report report = Report.messageReport(reporter, message, ReportReason.abuse, null, SNAPSHOT);

		assertThat(report.getTargetType()).isEqualTo(ReportTargetType.message);
		assertThat(report.getMessage()).isSameAs(message);
		assertThat(report.getAnswer()).isNull();
		assertThat(report.getReportedUser()).isSameAs(sender);
		assertThat(report.getAiReviewState()).isEqualTo(ReportAiReviewState.pending);
	}

	@Test
	void humanAnswerReportStoresAuthorAndCancelledAiWork() {
		User reporter = user(42L, "reporter");
		User author = user(77L, "author");
		Answer answer = answer(Answer.createHuman(10L, 77L, "human answer"), 500L);

		Report report = Report.answerReport(reporter, answer, author, ReportReason.spam, "detail", SNAPSHOT);

		assertThat(report.getTargetType()).isEqualTo(ReportTargetType.answer);
		assertThat(report.getMessage()).isNull();
		assertThat(report.getAnswer()).isSameAs(answer);
		assertThat(report.getReportedUser()).isSameAs(author);
		assertThat(report.getAiReviewState()).isEqualTo(ReportAiReviewState.cancelled);
	}

	@Test
	void aiAnswerReportHasNoReportedUserAndCancelledAiWork() {
		Answer answer = answer(Answer.createAi(10L, "AI answer"), 501L);

		Report report = Report.answerReport(
			user(42L, "reporter"), answer, null, ReportReason.etc, null, SNAPSHOT
		);

		assertThat(report.getTargetType()).isEqualTo(ReportTargetType.answer);
		assertThat(report.getAnswer()).isSameAs(answer);
		assertThat(report.getReportedUser()).isNull();
		assertThat(report.getAiReviewState()).isEqualTo(ReportAiReviewState.cancelled);
	}

	@Test
	void answerReportRejectsReportedUserThatDoesNotMatchAnswerKindOrAuthor() {
		User author = user(77L, "author");
		User other = user(88L, "other");
		Answer human = answer(Answer.createHuman(10L, 77L, "human"), 500L);
		Answer ai = answer(Answer.createAi(10L, "AI"), 501L);

		assertThatThrownBy(() -> Report.answerReport(
			user(42L, "reporter"), human, null, ReportReason.abuse, null, SNAPSHOT
		)).isInstanceOf(NullPointerException.class).hasMessageContaining("reportedUser");
		assertThatThrownBy(() -> Report.answerReport(
			user(42L, "reporter"), human, other, ReportReason.abuse, null, SNAPSHOT
		)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("author");
		assertThatThrownBy(() -> Report.answerReport(
			user(42L, "reporter"), ai, author, ReportReason.abuse, null, SNAPSHOT
		)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AI answer");
	}

	@Test
	void humanAnswerSelfReportRemainsAllowed() {
		User user = user(42L, "self");
		Answer answer = answer(Answer.createHuman(10L, 42L, "self answer"), 500L);

		Report report = Report.answerReport(user, answer, user, ReportReason.etc, null, SNAPSHOT);

		assertThat(report.getReporter()).isSameAs(report.getReportedUser());
	}

	@Test
	void scheduleReportStoresScheduleOwnerAndSkipsAiReview() {
		User reporter = user(42L, "reporter");
		User owner = user(77L, "owner");
		MeetingSchedule schedule = MeetingSchedule.createManaged(
			3L,
			77L,
			"용산 와인바에서 봅시다",
			"용산역 1번 출구",
			LocalDate.parse("2099-07-20"),
			LocalTime.parse("19:00"),
			null,
			1
		);

		Report report = Report.scheduleReport(reporter, schedule, owner, ReportReason.spam, "detail", SNAPSHOT);

		assertThat(report.getTargetType()).isEqualTo(ReportTargetType.schedule);
		assertThat(report.getSchedule()).isSameAs(schedule);
		assertThat(report.getMessage()).isNull();
		assertThat(report.getAnswer()).isNull();
		assertThat(report.getReportedUser()).isSameAs(owner);
		assertThat(report.getAiReviewState()).isEqualTo(ReportAiReviewState.cancelled);
	}

	private User user(Long id, String nickname) {
		User user = User.createEmailUser(
			nickname + "@example.com",
			"hash",
			nickname,
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			"KR"
		);
		setField(user, "id", id);
		return user;
	}

	private Answer answer(Answer answer, Long id) {
		setField(answer, "id", id);
		return answer;
	}

	private void setField(Object target, String fieldName, Object value) {
		try {
			java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			field.set(target, value);
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
