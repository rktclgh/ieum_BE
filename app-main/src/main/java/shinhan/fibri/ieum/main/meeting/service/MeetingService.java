package shinhan.fibri.ieum.main.meeting.service;

import java.time.OffsetDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

@Service
@RequiredArgsConstructor
public class MeetingService {

	private final MeetingRepository meetingRepository;
	private final MeetingParticipantRepository participantRepository;
	private final FileRepository fileRepository;
	private final PinWriter pinWriter;
	private final ChatRoomLifecycle chatRoomLifecycle;

	@Transactional
	public CreateMeetingResponse create(AuthenticatedUser principal, CreateMeetingRequest request) {
		UUID imageFileId = validateImage(request.imageFileId(), principal.userId());
		Long pinId = pinWriter.create(principal.userId(), PinType.meeting, request.lat(), request.lng());
		Meeting meeting = meetingRepository.save(Meeting.create(
			pinId,
			principal.userId(),
			request.title(),
			request.content(),
			request.placeName(),
			request.meetingAt(),
			request.maxMembers(),
			imageFileId,
			imageFileId
		));
		participantRepository.save(MeetingParticipant.join(meeting.getId(), principal.userId(), OffsetDateTime.now()));
		Long roomId = chatRoomLifecycle.createGroupRoom(meeting.getId(), principal.userId());
		return new CreateMeetingResponse(meeting.getId(), pinId, roomId);
	}

	private UUID validateImage(UUID imageFileId, Long userId) {
		if (imageFileId == null) {
			return null;
		}
		File file = fileRepository.findByFileIdAndUploaderId(imageFileId, userId)
			.filter(File::isUploaded)
			.filter(this::isImage)
			.orElseThrow(() -> new InvalidMeetingRequestException(
				"VALIDATION_FAILED",
				"imageFileId",
				"Invalid image"
			));
		return file.getFileId();
	}

	private boolean isImage(File file) {
		return file.getContentType() != null && file.getContentType().toLowerCase(java.util.Locale.ROOT).startsWith("image/");
	}
}
