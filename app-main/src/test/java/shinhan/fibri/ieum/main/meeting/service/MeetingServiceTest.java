package shinhan.fibri.ieum.main.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
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
import shinhan.fibri.ieum.main.meeting.dto.MeetingParticipantsResponse;
import shinhan.fibri.ieum.main.meeting.exception.HostCannotLeaveException;
import shinhan.fibri.ieum.main.meeting.exception.KickedMemberException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingFullException;
import shinhan.fibri.ieum.main.meeting.exception.InvalidMeetingRequestException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotFoundException;
import shinhan.fibri.ieum.main.meeting.exception.MeetingNotOpenException;
import shinhan.fibri.ieum.main.meeting.exception.NotHostException;
import shinhan.fibri.ieum.main.meeting.exception.ParticipantNotFoundException;
import shinhan.fibri.ieum.main.meeting.repository.MeetingDetailProjection;
import shinhan.fibri.ieum.main.meeting.repository.MeetingParticipantProjection;
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

	@Test
	void getDetailAssemblesMeetingDetailForJoinedMember() {
		UUID hostProfileFileId = UUID.fromString("11111111-1111-1111-1111-111111111111");
		UUID imageFileId = UUID.fromString("22222222-2222-2222-2222-222222222222");
		OffsetDateTime meetingAt = OffsetDateTime.parse("2026-07-10T19:00:00+09:00");
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-09T10:00:00+09:00");
		when(meetingRepository.findDetailById(3L))
			.thenReturn(Optional.of(detailRow(hostProfileFileId, imageFileId, meetingAt, createdAt)));
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(7L);
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L))
			.thenReturn(Optional.of(MeetingParticipant.join(3L, 42L, createdAt)));

		MeetingDetailResponse response = service.getDetail(principal(42L), 3L);

		assertThat(response.meetingId()).isEqualTo(3L);
		assertThat(response.pinId()).isEqualTo(11L);
		assertThat(response.roomId()).isEqualTo(9L);
		assertThat(response.title()).isEqualTo("저녁 모임");
		assertThat(response.content()).isEqualTo("같이 밥 먹어요");
		assertThat(response.placeName()).isEqualTo("동선역 2번 출구");
		assertThat(response.meetingAt()).isEqualTo(meetingAt);
		assertThat(response.status()).isEqualTo("open");
		assertThat(response.maxMembers()).isEqualTo(7);
		assertThat(response.participantCount()).isEqualTo(7L);
		assertThat(response.host().userId()).isEqualTo(1L);
		assertThat(response.host().nickname()).isEqualTo("오이정");
		assertThat(response.host().profileImageUrl()).isEqualTo("/api/v1/files/" + hostProfileFileId);
		assertThat(response.imageUrl()).isEqualTo("/api/v1/files/%s?v=display".formatted(imageFileId));
		assertThat(response.thumbnailUrl()).isEqualTo("/api/v1/files/%s?v=thumb".formatted(imageFileId));
		assertThat(response.location().lat()).isEqualTo(37.5);
		assertThat(response.location().lng()).isEqualTo(127.0);
		assertThat(response.myStatus()).isEqualTo("joined");
		assertThat(response.createdAt()).isEqualTo(createdAt);
	}

	@Test
	void getDetailReturnsHostStatusForHost() {
		when(meetingRepository.findDetailById(3L))
			.thenReturn(Optional.of(detailRow(null, null, OffsetDateTime.parse("2026-07-10T19:00:00+09:00"), OffsetDateTime.parse("2026-07-09T10:00:00+09:00"))));
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(1L);

		MeetingDetailResponse response = service.getDetail(principal(1L), 3L);

		assertThat(response.myStatus()).isEqualTo("host");
		assertThat(response.imageUrl()).isNull();
		assertThat(response.thumbnailUrl()).isNull();
	}

	@Test
	void getDetailThrowsWhenMeetingDoesNotExist() {
		when(meetingRepository.findDetailById(3L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getDetail(principal(42L), 3L))
			.isInstanceOf(MeetingNotFoundException.class)
			.hasMessage("Meeting not found");
	}

	@Test
	void getParticipantsReturnsJoinedParticipantsInJoinedAtOrder() {
		UUID profileFileId = UUID.fromString("33333333-3333-3333-3333-333333333333");
		OffsetDateTime hostJoinedAt = OffsetDateTime.parse("2026-07-09T10:00:00+09:00");
		OffsetDateTime memberJoinedAt = OffsetDateTime.parse("2026-07-09T11:00:00+09:00");
		Meeting meeting = Meeting.create(
			11L,
			1L,
			"저녁 모임",
			"같이 밥 먹어요",
			"동선역 2번 출구",
			OffsetDateTime.parse("2026-07-10T19:00:00+09:00"),
			7,
			null,
			null
		);
		setField(meeting, "id", 3L);
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findJoinedParticipantsByMeetingId(3L))
			.thenReturn(List.of(
				participantRow(1L, "오이정", null, hostJoinedAt),
				participantRow(42L, "참여자", profileFileId, memberJoinedAt)
			));

		MeetingParticipantsResponse response = service.getParticipants(principal(42L), 3L);

		assertThat(response.items()).hasSize(2);
		assertThat(response.items().get(0).userId()).isEqualTo(1L);
		assertThat(response.items().get(0).nickname()).isEqualTo("오이정");
		assertThat(response.items().get(0).profileImageUrl()).isNull();
		assertThat(response.items().get(0).isHost()).isTrue();
		assertThat(response.items().get(0).joinedAt()).isEqualTo(hostJoinedAt);
		assertThat(response.items().get(1).userId()).isEqualTo(42L);
		assertThat(response.items().get(1).profileImageUrl()).isEqualTo("/api/v1/files/" + profileFileId);
		assertThat(response.items().get(1).isHost()).isFalse();
		assertThat(response.items().get(1).joinedAt()).isEqualTo(memberJoinedAt);
	}

	@Test
	void getParticipantsThrowsWhenMeetingDoesNotExist() {
		when(meetingRepository.findByIdAndDeletedAtIsNull(3L)).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.getParticipants(principal(42L), 3L))
			.isInstanceOf(MeetingNotFoundException.class);
	}

	@Test
	void joinAddsNewParticipantToOpenMeetingAndGroupRoom() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.empty());
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(1L);

		JoinMeetingResponse response = service.join(principal(42L), 3L);

		assertThat(response.roomId()).isEqualTo(9L);
		ArgumentCaptor<MeetingParticipant> participantCaptor = ArgumentCaptor.forClass(MeetingParticipant.class);
		verify(participantRepository).save(participantCaptor.capture());
		assertThat(participantCaptor.getValue().getId().meetingId()).isEqualTo(3L);
		assertThat(participantCaptor.getValue().getId().userId()).isEqualTo(42L);
		assertThat(participantCaptor.getValue().getStatus()).isEqualTo(ParticipantStatus.joined);
		verify(chatRoomLifecycle).addMember(9L, 42L);
	}

	@Test
	void joinReturnsRoomIdWithoutMutatingWhenAlreadyJoined() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));

		JoinMeetingResponse response = service.join(principal(42L), 3L);

		assertThat(response.roomId()).isEqualTo(9L);
		verify(participantRepository, never()).save(any(MeetingParticipant.class));
		verify(participantRepository, never()).countByIdMeetingIdAndStatus(any(), any());
		verify(chatRoomLifecycle, never()).addMember(any(), any());
	}

	@Test
	void joinRejoinsLeftParticipantAndRestoresGroupRoomMember() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		participant.leave();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(1L);

		JoinMeetingResponse response = service.join(principal(42L), 3L);

		assertThat(response.roomId()).isEqualTo(9L);
		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.joined);
		verify(chatRoomLifecycle).addMember(9L, 42L);
	}

	@Test
	void joinRejectsPastOrClosedMeeting() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.join(principal(42L), 3L))
			.isInstanceOf(MeetingNotOpenException.class);
		verify(chatRoomLifecycle, never()).addMember(any(), any());
	}

	@Test
	void joinRejectsFullMeeting() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 2);
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.empty());
		when(participantRepository.countByIdMeetingIdAndStatus(3L, ParticipantStatus.joined)).thenReturn(2L);

		assertThatThrownBy(() -> service.join(principal(42L), 3L))
			.isInstanceOf(MeetingFullException.class);
		verify(participantRepository, never()).save(any(MeetingParticipant.class));
		verify(chatRoomLifecycle, never()).addMember(any(), any());
	}

	@Test
	void joinRejectsKickedParticipant() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		participant.kick();
		when(meetingRepository.findActiveByIdForUpdate(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));

		assertThatThrownBy(() -> service.join(principal(42L), 3L))
			.isInstanceOf(KickedMemberException.class);
		verify(chatRoomLifecycle, never()).addMember(any(), any());
	}

	@Test
	void leaveMarksParticipantLeftAndRemovesGroupRoomMember() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2026-07-01T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));

		service.leave(principal(42L), 3L);

		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.left);
		verify(chatRoomLifecycle).removeMember(9L, 42L);
	}

	@Test
	void leaveRejectsHost() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.leave(principal(1L), 3L))
			.isInstanceOf(HostCannotLeaveException.class);
		verify(chatRoomLifecycle, never()).removeMember(any(), any());
	}

	@Test
	void leaveRejectsMissingOrInactiveParticipant() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		participant.leave();
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));

		assertThatThrownBy(() -> service.leave(principal(42L), 3L))
			.isInstanceOf(ParticipantNotFoundException.class);
		verify(chatRoomLifecycle, never()).removeMember(any(), any());
	}

	@Test
	void leaveIgnoresMissingActiveChatMember() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));
		doThrow(new NotRoomMemberException()).when(chatRoomLifecycle).removeMember(9L, 42L);

		service.leave(principal(42L), 3L);

		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.left);
	}

	@Test
	void kickMarksParticipantKickedAndRemovesGroupRoomMember() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));

		service.kick(principal(1L), 3L, new KickMeetingRequest(42L));

		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.kicked);
		verify(chatRoomLifecycle).removeMember(9L, 42L);
	}

	@Test
	void kickRejectsNonHost() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.kick(principal(42L), 3L, new KickMeetingRequest(99L)))
			.isInstanceOf(NotHostException.class);
		verify(chatRoomLifecycle, never()).removeMember(any(), any());
	}

	@Test
	void kickRejectsHostTarget() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));

		assertThatThrownBy(() -> service.kick(principal(1L), 3L, new KickMeetingRequest(1L)))
			.isInstanceOf(InvalidMeetingRequestException.class)
			.hasMessage("Host cannot be kicked");
		verify(chatRoomLifecycle, never()).removeMember(any(), any());
	}

	@Test
	void kickRejectsMissingOrInactiveParticipant() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		participant.leave();
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));

		assertThatThrownBy(() -> service.kick(principal(1L), 3L, new KickMeetingRequest(42L)))
			.isInstanceOf(ParticipantNotFoundException.class);
		verify(chatRoomLifecycle, never()).removeMember(any(), any());
	}

	@Test
	void kickIgnoresMissingActiveChatMember() {
		Meeting meeting = meeting(3L, 1L, OffsetDateTime.parse("2099-07-10T19:00:00+09:00"), 7);
		MeetingParticipant participant = MeetingParticipant.join(3L, 42L, OffsetDateTime.parse("2026-07-09T10:00:00+09:00"));
		when(meetingRepository.findById(3L)).thenReturn(Optional.of(meeting));
		when(meetingRepository.findGroupRoomIdByMeetingId(3L)).thenReturn(Optional.of(9L));
		when(participantRepository.findByIdMeetingIdAndIdUserId(3L, 42L)).thenReturn(Optional.of(participant));
		doThrow(new NotRoomMemberException()).when(chatRoomLifecycle).removeMember(9L, 42L);

		service.kick(principal(1L), 3L, new KickMeetingRequest(42L));

		assertThat(participant.getStatus()).isEqualTo(ParticipantStatus.kicked);
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

	private Meeting meeting(Long id, Long hostId, OffsetDateTime meetingAt, int maxMembers) {
		Meeting meeting = Meeting.create(
			11L,
			hostId,
			"저녁 모임",
			"같이 밥 먹어요",
			"동선역 2번 출구",
			meetingAt,
			maxMembers,
			null,
			null
		);
		setField(meeting, "id", id);
		return meeting;
	}

	private AuthenticatedUser principal(Long userId) {
		return new AuthenticatedUser(userId, "user" + userId + "@example.com", UserRole.user, UserStatus.active);
	}

	private MeetingDetailProjection detailRow(
		UUID hostProfileFileId,
		UUID imageFileId,
		OffsetDateTime meetingAt,
		OffsetDateTime createdAt
	) {
		return new MeetingDetailProjection() {
			@Override
			public Long getMeetingId() {
				return 3L;
			}

			@Override
			public Long getPinId() {
				return 11L;
			}

			@Override
			public Long getRoomId() {
				return 9L;
			}

			@Override
			public String getTitle() {
				return "저녁 모임";
			}

			@Override
			public String getContent() {
				return "같이 밥 먹어요";
			}

			@Override
			public String getPlaceName() {
				return "동선역 2번 출구";
			}

			@Override
			public Instant getMeetingAt() {
				return meetingAt.toInstant();
			}

			@Override
			public String getStatus() {
				return "open";
			}

			@Override
			public int getMaxMembers() {
				return 7;
			}

			@Override
			public Long getHostUserId() {
				return 1L;
			}

			@Override
			public String getHostNickname() {
				return "오이정";
			}

			@Override
			public UUID getHostProfileFileId() {
				return hostProfileFileId;
			}

			@Override
			public UUID getImageFileId() {
				return imageFileId;
			}

			@Override
			public UUID getThumbnailFileId() {
				return imageFileId;
			}

			@Override
			public double getLatitude() {
				return 37.5;
			}

			@Override
			public double getLongitude() {
				return 127.0;
			}

			@Override
			public Instant getCreatedAt() {
				return createdAt.toInstant();
			}
		};
	}

	private MeetingParticipantProjection participantRow(
		Long userId,
		String nickname,
		UUID profileFileId,
		OffsetDateTime joinedAt
	) {
		return new MeetingParticipantProjection() {
			@Override
			public Long getUserId() {
				return userId;
			}

			@Override
			public String getNickname() {
				return nickname;
			}

			@Override
			public UUID getProfileFileId() {
				return profileFileId;
			}

			@Override
			public Instant getJoinedAt() {
				return joinedAt.toInstant();
			}
		};
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
