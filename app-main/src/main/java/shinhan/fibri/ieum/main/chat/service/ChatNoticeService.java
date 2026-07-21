package shinhan.fibri.ieum.main.chat.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatNotice;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.repository.ChatMemberRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatNoticeRepository;
import shinhan.fibri.ieum.common.chat.repository.ChatRoomRepository;
import shinhan.fibri.ieum.main.chat.dto.ChatNoticePageResponse;
import shinhan.fibri.ieum.main.chat.dto.ChatNoticeResponse;
import shinhan.fibri.ieum.main.chat.exception.ChatNoticeNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.ChatNoticeSourceNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.ChatRoomNotFoundException;
import shinhan.fibri.ieum.main.chat.exception.NotRoomMemberException;

@Service
@RequiredArgsConstructor
public class ChatNoticeService {

	private static final int DEFAULT_NOTICE_PAGE_SIZE = 20;
	private static final int MAX_NOTICE_PAGE_SIZE = 50;

	private final ChatMemberRepository chatMemberRepository;
	private final ChatNoticeRepository chatNoticeRepository;
	private final ChatRoomRepository chatRoomRepository;

	@Transactional
	public ChatNoticeRegistrationResult registerNotice(
		AuthenticatedUser principal,
		Long roomId,
		Long messageId
	) {
		ChatRoom room = findRoom(roomId);
		ChatMember member = findActiveMember(roomId, principal.userId());
		Message source = chatNoticeRepository.findVisibleSourceMessage(roomId, messageId, principal.userId())
			.orElseThrow(ChatNoticeSourceNotFoundException::new);
		Long noticeId = chatNoticeRepository.insertIgnore(roomId, source.getId(), principal.userId())
			.orElse(null);
		boolean created = noticeId != null;
		ChatNotice notice = created
			? findVisibleNotice(noticeId, roomId, principal.userId())
			: chatNoticeRepository.findVisibleByRoomIdAndMessageId(roomId, messageId, principal.userId())
				.orElseThrow(ChatNoticeNotFoundException::new);
		return new ChatNoticeRegistrationResult(toResponse(notice, member, room.getPinnedNoticeId()), created);
	}

	@Transactional(readOnly = true)
	public ChatNoticePageResponse listNotices(
		AuthenticatedUser principal,
		Long roomId,
		String cursor,
		Integer size
	) {
		ChatRoom room = findRoom(roomId);
		ChatMember member = findActiveMember(roomId, principal.userId());
		int pageSize = normalizePageSize(size);
		PageRequest pageRequest = PageRequest.of(0, pageSize + 1);
		ChatNoticeCursor decodedCursor = ChatNoticeCursor.decode(cursor);
		List<ChatNotice> notices = decodedCursor == null
			? chatNoticeRepository.findLatestVisible(roomId, principal.userId(), pageRequest)
			: chatNoticeRepository.findVisibleBeforeCursor(
				roomId,
				principal.userId(),
				decodedCursor.createdAt(),
				decodedCursor.noticeId(),
				pageRequest
			);
		boolean hasNext = notices.size() > pageSize;
		List<ChatNotice> pageItems = notices.stream().limit(pageSize).toList();
		String nextCursor = hasNext ? ChatNoticeCursor.encode(pageItems.getLast()) : null;
		Long pinnedNoticeId = room.getPinnedNoticeId();
		ChatNoticeResponse pinnedNotice = pinnedNoticeId == null
			? null
			: chatNoticeRepository.findVisibleByIdAndRoomId(pinnedNoticeId, roomId, principal.userId())
				.map(notice -> toResponse(notice, member))
				.orElse(null);
		return new ChatNoticePageResponse(
			pageItems.stream().map(notice -> toResponse(notice, member)).toList(),
			nextCursor,
			pinnedNotice
		);
	}

	@Transactional
	public ChatNoticeResponse pinNotice(AuthenticatedUser principal, Long roomId, Long noticeId) {
		ChatRoom room = findRoom(roomId);
		ChatMember member = findActiveMember(roomId, principal.userId());
		ChatNotice notice = findVisibleNotice(noticeId, roomId, principal.userId());
		room.pinNotice(noticeId);
		return toResponse(notice, member);
	}

	@Transactional
	public void unpinNotice(AuthenticatedUser principal, Long roomId, Long noticeId) {
		findRoom(roomId);
		findActiveMember(roomId, principal.userId());
		chatRoomRepository.clearPinnedNoticeIfMatches(roomId, noticeId);
	}

	private ChatRoom findRoom(Long roomId) {
		return chatRoomRepository.findById(roomId).orElseThrow(ChatRoomNotFoundException::new);
	}

	private ChatMember findActiveMember(Long roomId, Long userId) {
		return chatMemberRepository.findActiveByRoomIdAndUserId(roomId, userId)
			.orElseThrow(NotRoomMemberException::new);
	}

	private ChatNotice findVisibleNotice(Long noticeId, Long roomId, Long userId) {
		return chatNoticeRepository.findVisibleByIdAndRoomId(noticeId, roomId, userId)
			.orElseThrow(ChatNoticeNotFoundException::new);
	}

	private ChatNoticeResponse toResponse(ChatNotice notice, ChatMember member) {
		return ChatNoticeResponse.from(notice, member.getVisibleAfterMessageId(), member.getRoom().getPinnedNoticeId());
	}

	private ChatNoticeResponse toResponse(ChatNotice notice, ChatMember member, Long pinnedNoticeId) {
		return ChatNoticeResponse.from(notice, member.getVisibleAfterMessageId(), pinnedNoticeId);
	}

	private int normalizePageSize(Integer size) {
		if (size == null) {
			return DEFAULT_NOTICE_PAGE_SIZE;
		}
		if (size < 1) {
			throw new IllegalArgumentException("size must be positive");
		}
		return Math.min(size, MAX_NOTICE_PAGE_SIZE);
	}
}
