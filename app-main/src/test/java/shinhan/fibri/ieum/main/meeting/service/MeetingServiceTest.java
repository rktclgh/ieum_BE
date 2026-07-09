package shinhan.fibri.ieum.main.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.chat.service.ChatRoomLifecycle;
import shinhan.fibri.ieum.main.meeting.domain.Meeting;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipant;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingRequest;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingResponse;
import shinhan.fibri.ieum.main.meeting.exception.InvalidMeetingRequestException;
import shinhan.fibri.ieum.main.meeting.repository.MeetingParticipantRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRepository;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;

class MeetingServiceTest {

	private final MeetingRepository meetingRepository = mock(MeetingRepository.class);
	private final MeetingParticipantRepository participantRepository = mock(MeetingParticipantRepository.class);
	private final FileRepository fileRepository = mock(FileRepository.class);
	private final PinWriter pinWriter = mock(PinWriter.class);
	private final ChatRoomLifecycle chatRoomLifecycle = mock(ChatRoomLifecycle.class);
	private final MeetingService service = new MeetingService(
		meetingRepository,
		participantRepository,
		fileRepository,
		pinWriter,
		chatRoomLifecycle
	);

	@Test
	void createCreatesPinMeetingHostParticipantAndGroupRoomInOrder() {
		UUID imageFileId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		when(fileRepository.findByFileIdAndUploaderId(imageFileId, 42L))
			.thenReturn(Optional.of(uploadedFile(imageFileId, 42L, "image/jpeg")));
		when(pinWriter.create(42L, PinType.meeting, 37.5, 127.0)).thenReturn(11L);
		when(meetingRepository.save(any(Meeting.class))).thenAnswer(invocation -> {
			Meeting meeting = invocation.getArgument(0);
			setField(meeting, "id", 3L);
			return meeting;
		});
		when(chatRoomLifecycle.createGroupRoom(3L, 42L)).thenReturn(9L);

		CreateMeetingResponse response = service.create(principal(42L), request(imageFileId));

		assertThat(response.meetingId()).isEqualTo(3L);
		assertThat(response.pinId()).isEqualTo(11L);
		assertThat(response.roomId()).isEqualTo(9L);
		InOrder order = inOrder(pinWriter, meetingRepository, participantRepository, chatRoomLifecycle);
		order.verify(pinWriter).create(42L, PinType.meeting, 37.5, 127.0);
		order.verify(meetingRepository).save(any(Meeting.class));
		order.verify(participantRepository).save(any(MeetingParticipant.class));
		order.verify(chatRoomLifecycle).createGroupRoom(3L, 42L);
	}

	@Test
	void createRejectsImageNotOwnedByRequester() {
		UUID imageFileId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		when(fileRepository.findByFileIdAndUploaderId(imageFileId, 42L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.create(principal(42L), request(imageFileId)))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("Invalid image");
		verify(pinWriter, never()).create(any(), any(), any(Double.class), any(Double.class));
	}

	@Test
	void createRejectsNonImageFile() {
		UUID imageFileId = UUID.fromString("00000000-0000-0000-0000-000000000001");
		when(fileRepository.findByFileIdAndUploaderId(imageFileId, 42L))
			.thenReturn(Optional.of(uploadedFile(imageFileId, 42L, "text/plain")));

		assertThatThrownBy(() -> service.create(principal(42L), request(imageFileId)))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("Invalid image");
	}

	private CreateMeetingRequest request(UUID imageFileId) {
		return new CreateMeetingRequest(
			"저녁 모임",
			"같이 밥 먹어요",
			"동선역 2번 출구",
			OffsetDateTime.parse("2026-07-10T19:00:00+09:00"),
			7,
			37.5,
			127.0,
			imageFileId
		);
	}

	private AuthenticatedUser principal(Long userId) {
		return new AuthenticatedUser(userId, "user" + userId + "@example.com", UserRole.user, UserStatus.active);
	}

	private File uploadedFile(UUID fileId, Long uploaderId, String contentType) {
		File file = File.pending(fileId, uploaderId, "tmp/%s".formatted(fileId), contentType, 100L);
		file.markUploaded(OffsetDateTime.parse("2026-07-09T10:00:00+09:00"), contentType, 100L);
		return file;
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
