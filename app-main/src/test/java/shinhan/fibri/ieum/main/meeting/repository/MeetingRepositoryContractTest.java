package shinhan.fibri.ieum.main.meeting.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Lock;
import shinhan.fibri.ieum.main.meeting.domain.Meeting;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipant;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipantId;
import shinhan.fibri.ieum.main.meeting.domain.ParticipantStatus;

class MeetingRepositoryContractTest {

	@Test
	void meetingRepositoryUsesPessimisticLockForJoinRace() throws Exception {
		Method method = MeetingRepository.class.getMethod("findActiveByIdForUpdate", Long.class);

		assertThat(method.getReturnType().getTypeName()).isEqualTo("java.util.Optional");
		assertThat(method.getGenericReturnType().getTypeName()).contains(Meeting.class.getSimpleName());
		assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
	}

	@Test
	void participantRepositoryUsesPessimisticLockForParticipantStateRaces() throws Exception {
		Method method = MeetingParticipantRepository.class.getMethod(
			"findByIdMeetingIdAndIdUserIdForUpdate",
			Long.class,
			Long.class
		);

		assertThat(method.getReturnType().getTypeName()).isEqualTo("java.util.Optional");
		assertThat(method.getGenericReturnType().getTypeName()).contains(MeetingParticipant.class.getSimpleName());
		assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
	}

	@Test
	void participantRepositoryUsesCompositeIdAndJoinedCountContract() throws Exception {
		assertThat(MeetingParticipantRepository.class.getMethod("save", Object.class)).isNotNull();
		assertThat(MeetingParticipantRepository.class.getMethod("findById", Object.class)).isNotNull();
		assertThat(MeetingParticipantRepository.class.getMethod(
			"countByIdMeetingIdAndStatus",
			Long.class,
			ParticipantStatus.class
		)).isNotNull();
		assertThat(MeetingParticipantRepository.class.getGenericInterfaces()[0].getTypeName())
			.contains(MeetingParticipant.class.getSimpleName())
			.contains(MeetingParticipantId.class.getSimpleName());
	}

	@Test
	void participantRepositoryUsesPessimisticWriteLockForDepartureRace() throws Exception {
		Method method = MeetingParticipantRepository.class.getMethod(
			"findByIdMeetingIdAndIdUserIdForUpdate",
			Long.class,
			Long.class
		);

		assertThat(method.getReturnType().getTypeName()).isEqualTo("java.util.Optional");
		assertThat(method.getGenericReturnType().getTypeName()).contains(MeetingParticipant.class.getSimpleName());
		assertThat(method.getAnnotation(Lock.class).value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
	}
}
