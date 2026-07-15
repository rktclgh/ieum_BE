package shinhan.fibri.ieum.common.chat.repository;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.Message;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDatabase;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ChatRepositoryTest {

	@DynamicPropertySource
	static void configureDataSource(DynamicPropertyRegistry registry) {
		String databaseName = "common_chat_repository";
		CanonicalPostgresDatabase.recreateWithSchema(databaseName);
		registry.add("spring.datasource.url", () -> CanonicalPostgresContainer.jdbcUrl(databaseName));
		registry.add("spring.datasource.username", CanonicalPostgresContainer::username);
		registry.add("spring.datasource.password", CanonicalPostgresContainer::password);
		registry.add("spring.datasource.driver-class-name", CanonicalPostgresContainer::driverClassName);
		registry.add("spring.sql.init.mode", () -> "never");
	}

	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@Autowired
	private ChatMemberRepository chatMemberRepository;

	@Autowired
	private MessageRepository messageRepository;

	@Autowired
	private EntityManager entityManager;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void findsActiveRoomsForUserAndOptionalType() {
		User me = persist(user("rooms-me@example.com", "rooms-me"));
		User friend = persist(user("rooms-friend@example.com", "rooms-friend"));
		User other = persist(user("rooms-other@example.com", "rooms-other"));
		persistQuestion(10L, other);
		ChatRoom direct = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		ChatRoom question = chatRoomRepository.save(ChatRoom.question(10L, me.getId(), other.getId()));
		ChatRoom left = chatRoomRepository.save(ChatRoom.direct(me.getId(), other.getId()));
		chatMemberRepository.save(ChatMember.join(direct, me));
		chatMemberRepository.save(ChatMember.join(direct, friend));
		chatMemberRepository.save(ChatMember.join(question, me));
		ChatMember leftMember = chatMemberRepository.save(ChatMember.join(left, me));
		leftMember.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));

		assertThat(chatRoomRepository.findActiveRoomsByUserId(me.getId()))
			.extracting(ChatRoom::getId)
			.containsExactlyInAnyOrder(direct.getId(), question.getId());
		assertThat(chatRoomRepository.findActiveRoomsByUserIdAndRoomType(me.getId(), RoomType.direct))
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
	void findsActiveUserIdsByRoomIdOnlyForCurrentMembers() {
		User me = persist(user("active-ids-me@example.com", "active-ids-me"));
		User friend = persist(user("active-ids-friend@example.com", "active-ids-friend"));
		User leftUser = persist(user("active-ids-left@example.com", "active-ids-left"));
		User other = persist(user("active-ids-other@example.com", "active-ids-other"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		ChatRoom otherRoom = chatRoomRepository.save(ChatRoom.direct(me.getId(), other.getId()));
		chatMemberRepository.save(ChatMember.join(room, me));
		chatMemberRepository.save(ChatMember.join(room, friend));
		ChatMember left = chatMemberRepository.save(ChatMember.join(room, leftUser));
		left.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		chatMemberRepository.save(ChatMember.join(otherRoom, other));
		entityManager.flush();
		entityManager.clear();

		assertThat(chatMemberRepository.findActiveUserIdsByRoomId(room.getId()))
			.containsExactlyInAnyOrder(me.getId(), friend.getId());
	}

	@Test
	void findsActiveMembersByRoomAndRequestedUsersInOneQuery() {
		User me = persist(user("active-batch-me@example.com", "active-batch-me"));
		User friend = persist(user("active-batch-friend@example.com", "active-batch-friend"));
		User leftUser = persist(user("active-batch-left@example.com", "active-batch-left"));
		User other = persist(user("active-batch-other@example.com", "active-batch-other"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		ChatRoom otherRoom = chatRoomRepository.save(ChatRoom.direct(me.getId(), other.getId()));
		chatMemberRepository.save(ChatMember.join(room, me));
		chatMemberRepository.save(ChatMember.join(room, friend));
		ChatMember left = chatMemberRepository.save(ChatMember.join(room, leftUser));
		left.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		chatMemberRepository.save(ChatMember.join(otherRoom, other));
		entityManager.flush();
		entityManager.clear();

		List<ChatMember> members = chatMemberRepository.findActiveByRoomIdAndUserIds(
			room.getId(),
			List.of(me.getId(), friend.getId(), leftUser.getId(), other.getId())
		);

		assertThat(members)
			.extracting(member -> member.getUser().getId())
			.containsExactlyInAnyOrder(me.getId(), friend.getId());
	}

	@Test
	void findsAllRoomMembersWithUserFetched() {
		User me = persist(user("all-me@example.com", "all-me"));
		User friend = persist(user("all-friend@example.com", "all-friend"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		chatMemberRepository.save(ChatMember.join(room, me));
		ChatMember left = chatMemberRepository.save(ChatMember.join(room, friend));
		left.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		entityManager.flush();
		entityManager.clear();

		List<ChatMember> members = chatMemberRepository.findByRoom_Id(room.getId());

		// left 멤버 포함 전원 반환 + JOIN FETCH로 user가 즉시 로딩되어 clear 이후에도 접근 가능(N+1 없음).
		assertThat(members)
			.extracting(member -> member.getUser().getNickname())
			.containsExactlyInAnyOrder("all-me", "all-friend");
	}

	@Test
	void findsPushRecipientUserIdsInAscendingOrderExcludingSenderMutedLeftAndCutoffMembers() {
		User sender = persist(user("push-sender@example.com", "push-sender"));
		User eligibleLow = persist(user("push-low@example.com", "push-low"));
		User eligibleHigh = persist(user("push-high@example.com", "push-high"));
		User muted = persist(user("push-muted@example.com", "push-muted"));
		User left = persist(user("push-left@example.com", "push-left"));
		User hiddenByCutoff = persist(user("push-cutoff@example.com", "push-cutoff"));
		User otherRoom = persist(user("push-other-room@example.com", "push-other-room"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(sender.getId(), eligibleLow.getId()));
		ChatRoom anotherRoom = chatRoomRepository.save(ChatRoom.direct(sender.getId(), otherRoom.getId()));
		chatMemberRepository.save(ChatMember.join(room, sender));
		chatMemberRepository.save(ChatMember.join(room, eligibleHigh));
		chatMemberRepository.save(ChatMember.join(room, eligibleLow));
		ChatMember mutedMember = chatMemberRepository.save(ChatMember.join(room, muted));
		mutedMember.setNotifyEnabled(false);
		ChatMember leftMember = chatMemberRepository.save(ChatMember.join(room, left));
		leftMember.leave(OffsetDateTime.parse("2026-07-08T09:00:00+09:00"));
		ChatMember cutoffMember = chatMemberRepository.save(ChatMember.join(room, hiddenByCutoff));
		chatMemberRepository.save(ChatMember.join(anotherRoom, otherRoom));
		Message sent = messageRepository.save(Message.text(
			room,
			sender,
			"new-message",
			OffsetDateTime.parse("2026-07-08T10:00:00+09:00")
		));
		cutoffMember.hideHistoryThrough(sent.getId());
		entityManager.flush();
		entityManager.clear();

		assertThat(chatMemberRepository.findPushRecipientUserIds(room.getId(), sender.getId(), sent.getId()))
			.containsExactly(eligibleLow.getId(), eligibleHigh.getId());
	}

	@Test
	void scopesVisibleHistoryCursorUnreadAndLastMessageToViewer() {
		User returning = persist(user("visible-returning@example.com", "visible-returning"));
		User other = persist(user("visible-other@example.com", "visible-other"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(returning.getId(), other.getId()));
		ChatMember returningMember = chatMemberRepository.save(ChatMember.join(room, returning));
		chatMemberRepository.save(ChatMember.join(room, other));
		OffsetDateTime base = OffsetDateTime.parse("2026-07-08T10:00:00+09:00");
		messageRepository.save(Message.text(room, other, "old-1", base));
		Message old2 = messageRepository.save(Message.text(room, other, "old-2", base.plusMinutes(1)));
		returningMember.hideHistoryThrough(old2.getId());
		messageRepository.save(Message.text(room, other, "new-1", base.plusMinutes(3)));
		entityManager.flush();
		entityManager.clear();

		assertThat(messageRepository.findLatestVisibleMessages(
			room.getId(), returning.getId(), PageRequest.of(0, 10)
		))
			.extracting(Message::getContent)
			.containsExactly("new-1");
		assertThat(messageRepository.findLatestVisibleMessages(
			room.getId(), other.getId(), PageRequest.of(0, 10)
		))
			.extracting(Message::getContent)
			.containsExactly("new-1", "old-2", "old-1");
		assertThat(messageRepository.findVisibleMessagesBeforeCursor(
			room.getId(),
			returning.getId(),
			base.plusMinutes(2),
			Long.MAX_VALUE,
			PageRequest.of(0, 10)
		)).isEmpty();
		assertThat(messageRepository.countUnreadByRoomIds(returning.getId(), List.of(room.getId())))
			.extracting(MessageRepository.RoomUnreadCount::getRoomId, MessageRepository.RoomUnreadCount::getUnreadCount)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(room.getId(), 1L));
		assertThat(messageRepository.findLastVisibleMessagesByRoomIds(returning.getId(), List.of(room.getId())))
			.extracting(Message::getContent)
			.containsExactly("new-1");
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
		assertThat(messageRepository.findLastVisibleMessagesByRoomIds(me.getId(), List.of(room.getId())))
			.extracting(Message::getContent)
			.containsExactly("new");
	}

	@Test
	void findsLastMessagesByCreatedAtThenIdInsteadOfMaxIdInBulk() {
		User me = persist(user("last-order-me@example.com", "last-order-me"));
		User friend = persist(user("last-order-friend@example.com", "last-order-friend"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		chatMemberRepository.save(ChatMember.join(room, me));
		chatMemberRepository.save(ChatMember.join(room, friend));
		OffsetDateTime base = OffsetDateTime.parse("2026-07-08T10:00:00+09:00");
		Message latestByCreatedAt = messageRepository.save(Message.text(room, friend, "latest-created-at", base.plusMinutes(1)));
		Message olderWithHigherId = messageRepository.save(Message.text(room, friend, "older-higher-id", base));
		entityManager.flush();
		entityManager.clear();

		assertThat(olderWithHigherId.getId()).isGreaterThan(latestByCreatedAt.getId());
		List<Message> messages = messageRepository.findLastVisibleMessagesByRoomIds(
			me.getId(),
			List.of(room.getId())
		);

		assertThat(messages)
			.extracting(Message::getContent)
			.containsExactly("latest-created-at");
		assertThat(entityManagerFactory.getPersistenceUnitUtil().isLoaded(messages.get(0).getSender())).isTrue();
		assertThat(entityManagerFactory.getPersistenceUnitUtil().isLoaded(messages.get(0).getRoom())).isTrue();
	}

	@Test
	void findsLastMessagesByHigherIdWhenCreatedAtTies() {
		User me = persist(user("last-tie-me@example.com", "last-tie-me"));
		User friend = persist(user("last-tie-friend@example.com", "last-tie-friend"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		chatMemberRepository.save(ChatMember.join(room, me));
		chatMemberRepository.save(ChatMember.join(room, friend));
		OffsetDateTime createdAt = OffsetDateTime.parse("2026-07-08T10:00:00+09:00");
		messageRepository.save(Message.text(room, friend, "same-created-at-lower-id", createdAt));
		Message higherId = messageRepository.save(Message.text(room, friend, "same-created-at-higher-id", createdAt));
		entityManager.flush();
		entityManager.clear();

		assertThat(messageRepository.findLastVisibleMessagesByRoomIds(me.getId(), List.of(room.getId())))
			.extracting(Message::getContent)
			.containsExactly(higherId.getContent());
	}

	@Test
	void findsPersonalizedLastVisibleMessagesForRoomListEvents() {
		User fullHistory = persist(user("event-full@example.com", "event-full"));
		User cutoffViewer = persist(user("event-cutoff@example.com", "event-cutoff"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(fullHistory.getId(), cutoffViewer.getId()));
		chatMemberRepository.save(ChatMember.join(room, fullHistory));
		ChatMember cutoffMember = chatMemberRepository.save(ChatMember.join(room, cutoffViewer));
		Message latest = messageRepository.save(Message.text(
			room,
			fullHistory,
			"visible-only-to-full-history",
			OffsetDateTime.parse("2026-07-08T10:00:00+09:00")
		));
		cutoffMember.hideHistoryThrough(latest.getId());
		entityManager.flush();
		entityManager.clear();

		assertThat(messageRepository.findLastVisibleMessagesByRoomIdAndUserIds(
			room.getId(),
			List.of(fullHistory.getId(), cutoffViewer.getId())
		))
			.extracting(
				MessageRepository.UserLastVisibleMessage::getUserId,
				projection -> projection.getLastMessage().getContent()
			)
			.containsExactly(org.assertj.core.groups.Tuple.tuple(
				fullHistory.getId(),
				"visible-only-to-full-history"
			));
	}

	@Test
	void countsUnreadMessagesByRoomAndUsersInOneQuery() {
		User me = persist(user("unread-batch-me@example.com", "unread-batch-me"));
		User friend = persist(user("unread-batch-friend@example.com", "unread-batch-friend"));
		User quiet = persist(user("unread-batch-quiet@example.com", "unread-batch-quiet"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		ChatMember meMember = chatMemberRepository.save(ChatMember.join(room, me));
		ChatMember friendMember = chatMemberRepository.save(ChatMember.join(room, friend));
		ChatMember quietMember = chatMemberRepository.save(ChatMember.join(room, quiet));
		OffsetDateTime base = OffsetDateTime.parse("2026-07-08T10:00:00+09:00");
		meMember.markRead(base);
		friendMember.markRead(base.plusMinutes(1));
		quietMember.markRead(base.plusMinutes(10));
		messageRepository.save(Message.text(room, me, "old-own", base.minusMinutes(1)));
		messageRepository.save(Message.text(room, friend, "for-me", base.plusMinutes(2)));
		messageRepository.save(Message.text(room, quiet, "for-me-and-friend", base.plusMinutes(3)));
		entityManager.flush();
		entityManager.clear();

		assertThat(messageRepository.countUnreadByRoomIdAndUserIds(
			room.getId(),
			List.of(me.getId(), friend.getId(), quiet.getId())
		))
			.extracting(
				MessageRepository.UserUnreadCount::getUserId,
				MessageRepository.UserUnreadCount::getUnreadCount
			)
			.containsExactlyInAnyOrder(
				org.assertj.core.groups.Tuple.tuple(me.getId(), 2L),
				org.assertj.core.groups.Tuple.tuple(friend.getId(), 1L)
			);
	}

	@Test
	void findsMessagesByCursorDescending() {
		User me = persist(user("cursor-me@example.com", "cursor-me"));
		User friend = persist(user("cursor-friend@example.com", "cursor-friend"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		chatMemberRepository.save(ChatMember.join(room, me));
		chatMemberRepository.save(ChatMember.join(room, friend));
		OffsetDateTime base = OffsetDateTime.parse("2026-07-08T10:00:00+09:00");
		messageRepository.save(Message.text(room, me, "first", base));
		messageRepository.save(Message.text(room, friend, "second", base.plusMinutes(1)));
		Message third = messageRepository.save(Message.text(room, me, "third", base.plusMinutes(2)));
		Message deleted = messageRepository.save(Message.text(room, friend, "deleted", base.plusMinutes(3)));
		deleted.markDeleted(base.plusMinutes(4));
		entityManager.flush();
		entityManager.clear();

		assertThat(messageRepository.findLatestVisibleMessages(room.getId(), me.getId(), PageRequest.of(0, 3)))
			.extracting(Message::getContent)
			.containsExactly("third", "second", "first");
		assertThat(messageRepository.findVisibleMessagesBeforeCursor(
			room.getId(), me.getId(), base.plusMinutes(2), third.getId(), PageRequest.of(0, 2)
		))
			.extracting(Message::getContent)
			.containsExactly("second", "first");
	}

	@Test
	void findsReportContextAroundMessageWithLimitAndDeletedMessagesExcluded() {
		User me = persist(user("report-context-me@example.com", "report-context-me"));
		User friend = persist(user("report-context-friend@example.com", "report-context-friend"));
		ChatRoom room = chatRoomRepository.save(ChatRoom.direct(me.getId(), friend.getId()));
		OffsetDateTime base = OffsetDateTime.parse("2026-07-08T10:00:00+09:00");
		for (int i = 25; i >= 1; i--) {
			messageRepository.save(Message.text(room, me, "before-" + i, base.minusMinutes(i)));
		}
		Message deletedBefore = messageRepository.save(Message.text(room, me, "deleted-before", base.minusSeconds(30)));
		deletedBefore.markDeleted(base.plusMinutes(1));
		Message reported = messageRepository.save(Message.text(room, friend, "reported", base));
		Message deletedAfter = messageRepository.save(Message.text(room, me, "deleted-after", base.plusSeconds(30)));
		deletedAfter.markDeleted(base.plusMinutes(1));
		for (int i = 1; i <= 25; i++) {
			messageRepository.save(Message.text(room, me, "after-" + i, base.plusMinutes(i)));
		}
		entityManager.flush();
		entityManager.clear();

		assertThat(messageRepository.findContextBeforeMessage(room.getId(), base, reported.getId(), PageRequest.of(0, 20)))
			.extracting(Message::getContent)
			.containsExactly(
				"before-1",
				"before-2",
				"before-3",
				"before-4",
				"before-5",
				"before-6",
				"before-7",
				"before-8",
				"before-9",
				"before-10",
				"before-11",
				"before-12",
				"before-13",
				"before-14",
				"before-15",
				"before-16",
				"before-17",
				"before-18",
				"before-19",
				"before-20"
			);
		assertThat(messageRepository.findContextAfterMessage(room.getId(), base, reported.getId(), PageRequest.of(0, 20)))
			.extracting(Message::getContent)
			.containsExactly(
				"after-1",
				"after-2",
				"after-3",
				"after-4",
				"after-5",
				"after-6",
				"after-7",
				"after-8",
				"after-9",
				"after-10",
				"after-11",
				"after-12",
				"after-13",
				"after-14",
				"after-15",
				"after-16",
				"after-17",
				"after-18",
				"after-19",
				"after-20"
			);
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

	private void persistQuestion(Long questionId, User author) {
		entityManager.createNativeQuery("""
			INSERT INTO pins (pin_id, pin_type, author_id, location, address)
			VALUES (:pinId, 'question', :authorId, ST_GeogFromText('SRID=4326;POINT(127.0 37.5)'), 'address')
			""")
			.setParameter("pinId", questionId)
			.setParameter("authorId", author.getId())
			.executeUpdate();
		entityManager.createNativeQuery("""
			INSERT INTO questions (question_id, pin_id, author_id, title, content)
			VALUES (:questionId, :pinId, :authorId, 'question', 'content')
			""")
			.setParameter("questionId", questionId)
			.setParameter("pinId", questionId)
			.setParameter("authorId", author.getId())
			.executeUpdate();
	}

	@SpringBootApplication(scanBasePackages = "shinhan.fibri.ieum.common")
	@EntityScan(basePackageClasses = {User.class, ChatRoom.class, ChatMember.class, Message.class})
	static class TestApplication {
	}
}
