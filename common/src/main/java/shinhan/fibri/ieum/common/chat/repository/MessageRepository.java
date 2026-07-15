package shinhan.fibri.ieum.common.chat.repository;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.common.chat.domain.Message;

public interface MessageRepository extends JpaRepository<Message, Long> {

	@Query("""
		SELECT message
		FROM Message message
		JOIN FETCH message.sender
		WHERE message.room.id = :roomId
		  AND message.deletedAt IS NULL
		ORDER BY message.createdAt DESC, message.id DESC
		""")
	List<Message> findLatestMessagesByRoomId(
		@Param("roomId") Long roomId,
		Pageable pageable
	);

	@Query("""
		SELECT message
		FROM Message message
		JOIN FETCH message.sender
		WHERE message.room.id = :roomId
		  AND message.deletedAt IS NULL
		  AND (
			message.createdAt < :cursorCreatedAt
			OR (message.createdAt = :cursorCreatedAt AND message.id < :cursorMessageId)
		  )
		ORDER BY message.createdAt DESC, message.id DESC
		""")
	List<Message> findMessagesBeforeCursor(
		@Param("roomId") Long roomId,
		@Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
		@Param("cursorMessageId") Long cursorMessageId,
		Pageable pageable
	);

	@Query("""
		SELECT message.room.id AS roomId, COUNT(message) AS unreadCount
		FROM Message message
		JOIN ChatMember member ON member.room = message.room AND member.user.id = :userId
		WHERE message.room.id IN :roomIds
		  AND message.deletedAt IS NULL
		  AND message.sender.id <> :userId
		  AND (member.lastReadAt IS NULL OR message.createdAt > member.lastReadAt)
		GROUP BY message.room.id
		""")
	List<RoomUnreadCount> countUnreadByRoomIds(
		@Param("userId") Long userId,
		@Param("roomIds") List<Long> roomIds
	);

	@Query("""
		SELECT member.user.id AS userId, COUNT(message) AS unreadCount
		FROM ChatMember member
		JOIN Message message ON message.room = member.room
		WHERE member.room.id = :roomId
		  AND member.user.id IN :userIds
		  AND member.leftAt IS NULL
		  AND message.deletedAt IS NULL
		  AND message.sender.id <> member.user.id
		  AND (member.lastReadAt IS NULL OR message.createdAt > member.lastReadAt)
		GROUP BY member.user.id
		""")
	List<UserUnreadCount> countUnreadByRoomIdAndUserIds(
		@Param("roomId") Long roomId,
		@Param("userIds") List<Long> userIds
	);

	@Query("""
		SELECT message
		FROM Message message
		JOIN FETCH message.sender
		WHERE message.id IN (
			SELECT MAX(latest.id)
			FROM Message latest
			WHERE latest.room.id IN :roomIds
			  AND latest.deletedAt IS NULL
			GROUP BY latest.room.id
		)
		ORDER BY message.createdAt DESC, message.id DESC
		""")
	List<Message> findLastMessagesByRoomIds(@Param("roomIds") List<Long> roomIds);

	@Query("""
		SELECT message
		FROM Message message
		JOIN FETCH message.sender
		WHERE message.room.id = :roomId
		  AND message.deletedAt IS NULL
		  AND (
			message.createdAt < :reportedCreatedAt
			OR (message.createdAt = :reportedCreatedAt AND message.id < :reportedMessageId)
		  )
		ORDER BY message.createdAt DESC, message.id DESC
		""")
	List<Message> findContextBeforeMessage(
		@Param("roomId") Long roomId,
		@Param("reportedCreatedAt") OffsetDateTime reportedCreatedAt,
		@Param("reportedMessageId") Long reportedMessageId,
		Pageable pageable
	);

	@Query("""
		SELECT message
		FROM Message message
		JOIN FETCH message.sender
		WHERE message.room.id = :roomId
		  AND message.deletedAt IS NULL
		  AND (
			message.createdAt > :reportedCreatedAt
			OR (message.createdAt = :reportedCreatedAt AND message.id > :reportedMessageId)
		  )
		ORDER BY message.createdAt ASC, message.id ASC
		""")
	List<Message> findContextAfterMessage(
		@Param("roomId") Long roomId,
		@Param("reportedCreatedAt") OffsetDateTime reportedCreatedAt,
		@Param("reportedMessageId") Long reportedMessageId,
		Pageable pageable
	);

	interface RoomUnreadCount {
		Long getRoomId();

		Long getUnreadCount();
	}

	interface UserUnreadCount {
		Long getUserId();

		Long getUnreadCount();
	}
}
