package shinhan.fibri.ieum.main.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserSettings;
import shinhan.fibri.ieum.main.answer.domain.AnswerImage;
import shinhan.fibri.ieum.main.meeting.domain.Meeting;
import shinhan.fibri.ieum.main.meeting.domain.MeetingType;
import shinhan.fibri.ieum.main.question.domain.QuestionImage;

class SmallintEntityMappingTest {

	@Test
	void mapsSmallintSchemaColumnsToShortFields() throws NoSuchFieldException {
		assertThat(AnswerImage.class.getDeclaredField("sortOrder").getType()).isEqualTo(short.class);
		assertThat(QuestionImage.class.getDeclaredField("sortOrder").getType()).isEqualTo(short.class);
		assertThat(Meeting.class.getDeclaredField("maxMembers").getType()).isEqualTo(short.class);
		assertThat(UserSettings.class.getDeclaredField("notifyRadiusKm").getType()).isEqualTo(short.class);
	}

	@Test
	void rejectsSortOrderOutsideSmallintRange() {
		assertThatThrownBy(() -> AnswerImage.link(1L, UUID.randomUUID(), Short.MAX_VALUE + 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> AnswerImage.link(1L, UUID.randomUUID(), -1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> QuestionImage.link(1L, UUID.randomUUID(), Short.MAX_VALUE + 1))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> QuestionImage.link(1L, UUID.randomUUID(), -1))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void acceptsRepresentableValuesAndKeepsPublicGettersAsInts() throws NoSuchMethodException {
		AnswerImage answerImage = AnswerImage.link(1L, UUID.randomUUID(), Short.MAX_VALUE);
		QuestionImage questionImage = QuestionImage.link(1L, UUID.randomUUID(), Short.MAX_VALUE);
		Meeting meeting = Meeting.create(
			1L,
			2L,
			MeetingType.one_time,
			"title",
			null,
			null,
			Short.MAX_VALUE,
			null,
			null
		);
		UserSettings settings = UserSettings.defaultFor(User.createEmailUser(
			"user@example.com",
			"hash",
			"nickname",
			LocalDate.of(1995, 5, 20),
			GenderType.female,
			"KR"
		));
		settings.update("ko", false, true, true, true, true, 10);

		assertThat(answerImage.getSortOrder()).isEqualTo((int) Short.MAX_VALUE);
		assertThat(questionImage.getSortOrder()).isEqualTo((int) Short.MAX_VALUE);
		assertThat(meeting.getMaxMembers()).isEqualTo((int) Short.MAX_VALUE);
		assertThat(settings.getNotifyRadiusKm()).isEqualTo(10);
		assertThat(AnswerImage.class.getMethod("getSortOrder").getReturnType()).isEqualTo(int.class);
		assertThat(QuestionImage.class.getMethod("getSortOrder").getReturnType()).isEqualTo(int.class);
		assertThat(Meeting.class.getMethod("getMaxMembers").getReturnType()).isEqualTo(int.class);
		assertThat(UserSettings.class.getMethod("getNotifyRadiusKm").getReturnType()).isEqualTo(int.class);
	}

	@Test
	void rejectsMaxMembersOutsideSmallintRange() {
		assertThatThrownBy(() -> Meeting.create(
			1L,
			2L,
			MeetingType.one_time,
			"title",
			null,
			null,
			Short.MAX_VALUE + 1,
			null,
			null
		)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void rejectsNotifyRadiusOutsideSmallintRange() {
		UserSettings settings = UserSettings.defaultFor(User.createEmailUser(
			"user@example.com",
			"hash",
			"nickname",
			LocalDate.of(1995, 5, 20),
			GenderType.female,
			"KR"
		));

		assertThatThrownBy(() -> settings.update(
			"ko",
			false,
			true,
			true,
			true,
			true,
			Short.MAX_VALUE + 1
		)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> settings.update(
			"ko",
			false,
			true,
			true,
			true,
			true,
			-1
		)).isInstanceOf(IllegalArgumentException.class);
	}
}
