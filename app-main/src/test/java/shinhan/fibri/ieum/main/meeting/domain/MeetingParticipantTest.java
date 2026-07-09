package shinhan.fibri.ieum.main.meeting.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

class MeetingParticipantTest {

	@Test
	void joinCreatesJoinedParticipant() {
		OffsetDateTime now = OffsetDateTime.parse("2026-07-09T10:00:00+09:00");

		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, now);

		assertThat(participant.getId().meetingId()).isEqualTo(3L);
		assertThat(participant.getId().userId()).isEqualTo(42L);
		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.joined);
		assertThat(participant.getJoinedAt()).isEqualTo(now);
	}

	@Test
	void leaveRejoinAndKickUpdateStatus() {
		MeetingParticipant participant = MeetingParticipant.join(
			3L,
			42L,
			OffsetDateTime.parse("2026-07-09T10:00:00+09:00")
		);

		participant.leave();
		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.left);

		OffsetDateTime rejoinedAt = OffsetDateTime.parse("2026-07-09T11:00:00+09:00");
		participant.rejoin(rejoinedAt);
		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.joined);
		assertThat(participant.getJoinedAt()).isEqualTo(rejoinedAt);

		participant.kick();
		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.kicked);
	}

	@Test
	void kickedParticipantCannotRejoin() {
		MeetingParticipant participant = MeetingParticipant.join(
			3L,
			42L,
			OffsetDateTime.parse("2026-07-09T10:00:00+09:00")
		);

		participant.kick();
		participant.rejoin(OffsetDateTime.parse("2026-07-09T11:00:00+09:00"));

		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.kicked);
	}
}
