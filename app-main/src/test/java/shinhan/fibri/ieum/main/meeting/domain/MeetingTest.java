package shinhan.fibri.ieum.main.meeting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MeetingTest {

	@Test
	void createInitializesOpenMeetingWithLocationPinAndFiles() {
		OffsetDateTime meetingAt = OffsetDateTime.parse("2026-07-10T19:00:00+09:00");
		UUID imageFileId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID thumbnailFileId = UUID.fromString("00000000-0000-0000-0000-000000000002");

		Meeting meeting = Meeting.create(
			11L,
			42L,
			MeetingType.one_time,
			"저녁 모임",
			"같이 밥 먹어요",
			meetingAt,
			7,
			imageFileId,
			thumbnailFileId
		);

		assertThat(meeting.getPinId()).isEqualTo(11L);
		assertThat(meeting.getHostId()).isEqualTo(42L);
		assertThat(meeting.getMeetingAt()).isEqualTo(meetingAt);
		assertThat(meeting.getMaxMembers()).isEqualTo(7);
		assertThat(meeting.getImageFileId()).isEqualTo(imageFileId);
		assertThat(meeting.getThumbnailFileId()).isEqualTo(thumbnailFileId);
		assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.open);
		assertThat(meeting.getDeletedAt()).isNull();
	}

	@Test
	void closeOnlyAllowsOpenMeeting() {
		Meeting meeting = openMeeting();

		meeting.close();

		assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.closed);
		assertThatThrownBy(meeting::close).isInstanceOf(IllegalStateException.class);
	}

	@Test
	void createAllowsUnscheduledMeetingAndClearsLegacyCache() {
		Meeting meeting = Meeting.create(
			11L,
			42L,
			MeetingType.one_time,
			"일정 미정 모임",
			null,
			null,
			7,
			null,
			null
		);

		assertThat(meeting.getMeetingAt()).isNull();

		meeting.updateMeetingAtCache(OffsetDateTime.parse("2026-07-20T19:00:00+09:00"));
		meeting.clearMeetingAtCache();

		assertThat(meeting.getMeetingAt()).isNull();
	}

	@Test
	void cancelSoftDeletesMeeting() {
		Meeting meeting = openMeeting();
		OffsetDateTime cancelledAt = OffsetDateTime.parse("2026-07-09T12:00:00+09:00");

		meeting.cancel(cancelledAt);

		assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.cancelled);
		assertThat(meeting.getDeletedAt()).isEqualTo(cancelledAt);
	}

	@Test
	void cancelRejectsAlreadyCancelledMeeting() {
		Meeting meeting = openMeeting();
		OffsetDateTime firstCancelledAt = OffsetDateTime.parse("2026-07-09T12:00:00+09:00");
		meeting.cancel(firstCancelledAt);

		assertThatThrownBy(() -> meeting.cancel(OffsetDateTime.parse("2026-07-10T12:00:00+09:00")))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("Meeting is already cancelled");
		assertThat(meeting.getDeletedAt()).isEqualTo(firstCancelledAt);
	}

	private Meeting openMeeting() {
		return Meeting.create(
			11L,
			42L,
			MeetingType.one_time,
			"저녁 모임",
			null,
			OffsetDateTime.parse("2026-07-10T19:00:00+09:00"),
			7,
			null,
			null
		);
	}
}
