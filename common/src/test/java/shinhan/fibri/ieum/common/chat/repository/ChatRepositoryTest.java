package shinhan.fibri.ieum.common.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.domain.PageRequest;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.RoomType;

@DataJpaTest
class ChatRepositoryTest {

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private ChatMemberRepository chatMemberRepository;

	@Autowired
	private MessageRepository messageRepository;

	@Autowired
	private EntityManager entityManager;

	@Test
	void findsActiveRoomsForUserAndOptionalType() {
		User me = persist(user("rooms-me@example.com", "rooms-me"));
		User friend = persist(user("rooms-friend@example.com", "rooms-friend"));
		User other = persist(user("rooms-other@example.com", "rooms-other"));
		ChatRoom direct = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		ChatRoom question = chatRoomRepository.save(ChatRoom.question(10L, me.getId(), other.getId()));
		ChatRoom left = chatRoomRepository.save(ChatRoom.direct(me.getId(), other.getId()));
		chatMemberRepository.save(ChatMember.join(direct, me));
		chatMemberRepository.save(ChatMember.join(direct, friend));
		chatMemberRepository.save(ChatMember.join(question, me));
		ChatMember leftMember = chatMemberRepository.save(ChatMember.join(left, me));
		leftMember.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));

		assertThat(chatRoomRepository.findActiveRoomsByUserId(me.getId(), null))
			.extracting(ChatRoom::getId)
			.containsExactlyInAnyOrder(direct.getId(), question.getId());
		assertThat(chatRoomRepository.findActiveRoomsByUserId(me.getId(), RoomType.direct))
			.extracting(ChatRoom::getId)
			.containsExactly(direct.getId());
	}

	@Test
	void findsActiveMemberOnlyWhenLeftAtIsNull() {
		User me = persist(user("member-me@example.com", "member-me"));
		User friend = persist(user("member-friend@example.com", "member-friend"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		ChatMember active = chatMemberRepository.save(ChatMember.join(room, me));
		ChatMember left = chatMemberRepository.save(ChatMember.join(room, friend));
		left.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));

		assertThat(chatMemberRepository.findActiveByRoomIdAndUserId(room.getId(), me.getId()))
			.contains(active);
		assertThat(chatMemberRepository.findActiveByRoomIdAndUserId(room.getId(), friend.getId()))
			.isEmpty();
	}

	@Test
	void restoresLeftMembersExceptSender() {
		User me = persist(user("restore-me@example.com", "restore-me"));
		User friend = persist(user("restore-friend@example.com", "restore-friend"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		chatMemberRepository.save(ChatMember.join(room, me));
		ChatMember left = chatMemberRepository.save(ChatMember.join(room, friend));
		left.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		entityManager.flush();
		entityManager.clear();

		int restored = chatMemberRepository.restoreLeftMembersByRoomIdExceptSender(room.getId(), me.getId());
		entityManager.flush();
		entityManager.clear();

		assertThat(restored).isEqualTo(1);
		assertThat(chatMemberRepository.findActiveByRoomIdAndUserId(room.getId(), friend.getId())).isPresent();
	}

	@Test
	void countsUnreadMessagesAndFindsLastMessagesInBulk() {
		User me = persist(user("unread-me@example.com", "unread-me"));
		User friend = persist(user("unread-friend@example.com", "unread-friend"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		ChatMember meMember = chatMemberRepository.save(ChatMember.join(room, me));
		chatMemberRepository.save(ChatMember.join(room, friend));
		OffsetDateTime readAt = OffsetDateTime.parse("2026-07-08T10:00:00+09:00");
		meMember.markRead(readAt);
		messageRepository.save(Message.text(room, friend, "old", readAt.minusMinutes(1)));
		messageRepository.save(Message.text(room, me, "own", readAt.plusMinutes(1)));
		messageRepository.save(Message.text(room, friend, "new", readAt.plusMinutes(2)));
		entityManager.flush();
		entityManager.clear();

		assertThat(messageRepository.countUnreadByRoomIds(me.getId(), List.of(room.getId())))
			.extracting(MessageRepository.RoomUnreadCount::getRoomId, MessageRepository.RoomUnreadCount::getUnreadCount)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(room.getId(), 1L));
		assertThat(messageRepository.findLastMessagesByRoomIds(List.of(room.getId())))
			.extracting(Message::getContent)
			.containsExactly("new");
	}

	@Test
	void findsMessagesByCursorDescending() {
		User me = persist(user("cursor-me@example.com", "cursor-me"));
		User friend = persist(user("cursor-friend@example.com", "cursor-friend"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		OffsetDateTime base = OffsetDateTime.parse("2026-07-08T10:00:00+09:00");
		messageRepository.save(Message.text(room, me, "first", base));
		messageRepository.save(Message.text(room, friend, "second", base.plusMinutes(1)));
		Message third = messageRepository.save(Message.text(room, me, "third", base.plusMinutes(2)));
		Message deleted = messageRepository.save(Message.text(room, friend, "deleted", base.plusMinutes(3)));
		deleted.markDeleted(base.plusMinutes(4));
		entityManager.flush();
		entityManager.clear();

		assertThat(messageRepository.findMessagesBeforeCursor(room.getId(), null, null, PageRequest.of(0, 3)))
			.extracting(Message::getContent)
			.containsExactly("third", "second", "first");
		assertThat(messageRepository.findMessagesBeforeCursor(room.getId(), base.plusMinutes(2), third.getId(), PageRequest.of(0, 2)))
			.extracting(Message::getContent)
			.containsExactly("second", "first");
	}

	private User user(String email, String nickname) {
		return User.createEmailUser(
			email,
			"hash",
			nickname,
			LocalDate.of(1995, 1, 1),
			GenderType.female,
			"KR"
		);
	}

	private User persist(User user) {
		entityManager.persist(user);
		return user;
	}

	@SpringBootApplication(scanBasePackages = "shinhan.fibri.ieum.common")
	@EntityScan(basePackageClasses = {User.class, ChatRoom.class, ChatMember.class, Message.class})
	static class TestApplication {
	}
}
