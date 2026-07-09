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
import shinhan.fibri.ieum.main.meeting.dto.MeetingDetailResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingHostSummary;
import shinhan.fibri.ieum.main.meeting.dto.MeetingLocation;
import shinhan.fibri.ieum.main.meeting.exception.InvalidMeetingRequestException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotFoundException;
import shinhan.fibri.ieum.main.meeting.repository.MeetingDetailProjection;
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

	@Transactional(readOnly = true)
	public MeetingDetailResponse getDetail(AuthenticatedUser principal, Long meetingId) {
		MeetingDetailProjection detail = meetingRepository.findDetailById(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		long participantCount = participantRepository.countByIdMeetingIdAndStatus(
			meetingId,
			shinhan.fibri.ieum.main.meeting.domain.ParticipantStatus.joined
		);
		return new MeetingDetailResponse(
			detail.getMeetingId(),
			detail.getPinId(),
			detail.getRoomId(),
			detail.getTitle(),
			detail.getContent(),
			detail.getPlaceName(),
			detail.getMeetingAt(),
			detail.getStatus(),
			detail.getMaxMembers(),
			participantCount,
			new MeetingHostSummary(
				detail.getHostUserId(),
				detail.getHostNickname(),
				fileUrl(detail.getHostProfileFileId(), null)
			),
			fileUrl(detail.getImageFileId(), "display"),
			fileUrl(detail.getThumbnailFileId(), "thumb"),
			new MeetingLocation(detail.getLatitude(), detail.getLongitude()),
			myStatus(principal.userId(), detail),
			detail.getCreatedAt()
		);
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

	private String myStatus(Long userId, MeetingDetailProjection detail) {
		if (detail.getHostUserId().equals(userId)) {
			return "host";
		}
		return participantRepository.findByIdMeetingIdAndIdUserId(detail.getMeetingId(), userId)
			.map(participant -> participant.getStatus().name())
			.orElse("none");
	}

	private String fileUrl(UUID fileId, String variant) {
		if (fileId == null) {
			return null;
		}
		String url = "/api/v1/files/" + fileId;
		return variant == null ? url : url + "?v=" + variant;
	}
}
