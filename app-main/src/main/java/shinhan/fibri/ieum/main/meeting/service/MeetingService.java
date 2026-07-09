package shinhan.fibri.ieum.main.meeting.service;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.file.domain.File;
import shinhan.fibri.ieum.common.file.repository.FileRepository;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;
import shinhan.fibri.ieum.main.chat.service.ChatRoomLifecycle;
import shinhan.fibri.ieum.main.meeting.domain.Meeting;
import shinhan.fibri.ieum.main.meeting.domain.MeetingParticipant;
import shinhan.fibri.ieum.main.meeting.domain.ParticipantStatus;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingRequest;
import shinhan.fibri.ieum.main.meeting.dto.CreateMeetingResponse;
import shinhan.fibri.ieum.main.meeting.dto.JoinMeetingResponse;
import shinhan.fibri.ieum.main.meeting.dto.KickMeetingRequest;
import shinhan.fibri.ieum.main.meeting.dto.MeetingDetailResponse;
import shinhan.fibri.ieum.main.meeting.dto.MeetingHostSummary;
import shinhan.fibri.ieum.main.meeting.dto.MeetingLocation;
import shinhan.fibri.ieum.main.meeting.dto.MeetingParticipantItem;
import shinhan.fibri.ieum.main.meeting.dto.MeetingParticipantsResponse;
import shinhan.fibri.ieum.main.meeting.exception.HostCannotLeaveException;
import shinhan.fibri.ieum.main.meeting.exception.InvalidMeetingRequestException;
import shinhan.fibri.ieum.main.meeting.exception.KickedMemberException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingFullException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotOpenException;
import shinhan.fibri.ieum.main.meeting.exception.NotHostException;
import shinhan.fibri.ieum.main.meeting.exception.ParticipantNotFoundException;
import shinhan.fibri.ieum.main.meeting.repository.MeetingDetailProjection;
import shinhan.fibri.ieum.main.meeting.repository.MeetingParticipantRepository;
import shinhan.fibri.ieum.main.meeting.repository.MeetingRepository;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.repository.PinWriter;

@Service
@RequiredArgsConstructor
public class MeetingService {

	private static final ZoneId RESPONSE_ZONE = ZoneId.of("Asia/Seoul");

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
			detail.getMeetingAt().atZone(RESPONSE_ZONE).toOffsetDateTime(),
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
			detail.getCreatedAt().atZone(RESPONSE_ZONE).toOffsetDateTime()
		);
	}

	@Transactional(readOnly = true)
	public MeetingParticipantsResponse getParticipants(AuthenticatedUser principal, Long meetingId) {
		Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		List<MeetingParticipantItem> items = participantRepository.findJoinedParticipantsByMeetingId(meetingId)
			.stream()
			.map(row -> new MeetingParticipantItem(
				row.getUserId(),
				row.getNickname(),
				fileUrl(row.getProfileFileId(), null),
				meeting.getHostId().equals(row.getUserId()),
				row.getJoinedAt().atZone(RESPONSE_ZONE).toOffsetDateTime()
			))
			.toList();
		return new MeetingParticipantsResponse(items);
	}

	@Transactional
	public JoinMeetingResponse join(AuthenticatedUser principal, Long meetingId) {
		Meeting meeting = meetingRepository.findActiveByIdForUpdate(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		if (!meeting.isJoinable(OffsetDateTime.now())) {
			throw new MeetingNotOpenException();
		}
		Long roomId = meetingRepository.findGroupRoomIdByMeetingId(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		return participantRepository.findByIdMeetingIdAndIdUserId(meetingId, principal.userId())
			.map(participant -> joinExistingParticipant(meeting, participant, roomId, principal.userId()))
			.orElseGet(() -> joinNewParticipant(meeting, roomId, principal.userId()));
	}

	private JoinMeetingResponse joinExistingParticipant(
		Meeting meeting,
		MeetingParticipant participant,
		Long roomId,
		Long userId
	) {
		if (participant.getStatus() == ParticipantStatus.kicked) {
			throw new KickedMemberException();
		}
		if (participant.getStatus() == ParticipantStatus.joined) {
			return new JoinMeetingResponse(roomId);
		}
		ensureHasCapacity(meeting);
		participant.rejoin(OffsetDateTime.now());
		chatRoomLifecycle.addMember(roomId, userId);
		return new JoinMeetingResponse(roomId);
	}

	private JoinMeetingResponse joinNewParticipant(Meeting meeting, Long roomId, Long userId) {
		ensureHasCapacity(meeting);
		participantRepository.save(MeetingParticipant.join(meeting.getId(), userId, OffsetDateTime.now()));
		chatRoomLifecycle.addMember(roomId, userId);
		return new JoinMeetingResponse(roomId);
	}

	private void ensureHasCapacity(Meeting meeting) {
		long joinedCount = participantRepository.countByIdMeetingIdAndStatus(
			meeting.getId(),
			ParticipantStatus.joined
		);
		if (joinedCount >= meeting.getMaxMembers()) {
			throw new MeetingFullException();
		}
	}

	@Transactional
	public void leave(AuthenticatedUser principal, Long meetingId) {
		Meeting meeting = meetingRepository.findById(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		if (meeting.getHostId().equals(principal.userId())) {
			throw new HostCannotLeaveException();
		}
		MeetingParticipant participant = participantRepository
			.findByIdMeetingIdAndIdUserId(meetingId, principal.userId())
			.filter(row -> row.getStatus() == ParticipantStatus.joined)
			.orElseThrow(ParticipantNotFoundException::new);
		Long roomId = meetingRepository.findGroupRoomIdByMeetingId(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		participant.leave();
		try {
			chatRoomLifecycle.removeMember(roomId, principal.userId());
		} catch (NotRoomMemberException ignored) {
			// meeting_participants is the source of truth; tolerate older chat-only leave history.
		}
	}

	@Transactional
	public void kick(AuthenticatedUser principal, Long meetingId, KickMeetingRequest request) {
		Meeting meeting = meetingRepository.findById(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		if (!meeting.getHostId().equals(principal.userId())) {
			throw new NotHostException();
		}
		if (meeting.getHostId().equals(request.userId())) {
			throw new InvalidMeetingRequestException("VALIDATION_FAILED", "userId", "Host cannot be kicked");
		}
		MeetingParticipant participant = participantRepository
			.findByIdMeetingIdAndIdUserId(meetingId, request.userId())
			.filter(row -> row.getStatus() == ParticipantStatus.joined)
			.orElseThrow(ParticipantNotFoundException::new);
		Long roomId = meetingRepository.findGroupRoomIdByMeetingId(meetingId)
			.orElseThrow(MeetingNotFoundException::new);
		participant.kick();
		try {
			chatRoomLifecycle.removeMember(roomId, request.userId());
		} catch (NotRoomMemberException ignored) {
			// meeting_participants is the source of truth; tolerate older chat-only leave history.
		}
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
