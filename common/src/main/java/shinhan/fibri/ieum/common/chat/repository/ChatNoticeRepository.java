package shinhan.fibri.ieum.common.chat.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.common.chat.domain.ChatNotice;
import shinhan.fibri.ieum.common.chat.domain.Message;

public interface ChatNoticeRepository extends JpaRepository<ChatNotice, Long> {

	@Query(value = """
		INSERT INTO chat_notices (room_id, message_id, created_by, created_at)
		VALUES (:roomId, :messageId, :createdBy, now())
		ON CONFLICT (room_id, message_id) DO NOTHING
		RETURNING notice_id
		""", nativeQuery = true)
	Optional<Long> insertIgnore(
		@Param("roomId") Long roomId,
		@Param("messageId") Long messageId,
		@Param("createdBy") Long createdBy
	);

	@Query("""
		SELECT message
		FROM Message message
		JOIN FETCH message.room
		JOIN FETCH message.sender
		LEFT JOIN FETCH message.replyTo replyTo
		LEFT JOIN FETCH replyTo.sender
		JOIN ChatMember member ON member.room = message.room AND member.user.id = :userId
		WHERE message.room.id = :roomId
		  AND message.id = :messageId
		  AND member.leftAt IS NULL
		  AND message.id > member.visibleAfterMessageId
		  AND message.deletedAt IS NULL
		  AND message.messageType = shinhan.fibri.ieum.common.chat.domain.MessageType.user
		  AND message.content IS NOT NULL
		""")
	Optional<Message> findVisibleSourceMessage(
		@Param("roomId") Long roomId,
		@Param("messageId") Long messageId,
		@Param("userId") Long userId
	);

	@Query("""
		SELECT notice
		FROM ChatNotice notice
		JOIN FETCH notice.room room
		JOIN FETCH notice.message message
		JOIN FETCH message.sender
		LEFT JOIN FETCH message.replyTo replyTo
		LEFT JOIN FETCH replyTo.sender
		JOIN ChatMember member ON member.room = room AND member.user.id = :userId
		WHERE room.id = :roomId
		  AND member.leftAt IS NULL
		  AND message.id > member.visibleAfterMessageId
		  AND message.deletedAt IS NULL
		  AND message.messageType = shinhan.fibri.ieum.common.chat.domain.MessageType.user
		  AND message.content IS NOT NULL
		ORDER BY notice.createdAt DESC, notice.id DESC
		""")
	List<ChatNotice> findLatestVisible(
		@Param("roomId") Long roomId,
		@Param("userId") Long userId,
		Pageable pageable
	);

	@Query("""
		SELECT notice
		FROM ChatNotice notice
		JOIN FETCH notice.room room
		JOIN FETCH notice.message message
		JOIN FETCH message.sender
		LEFT JOIN FETCH message.replyTo replyTo
		LEFT JOIN FETCH replyTo.sender
		JOIN ChatMember member ON member.room = room AND member.user.id = :userId
		WHERE room.id = :roomId
		  AND member.leftAt IS NULL
		  AND message.id > member.visibleAfterMessageId
		  AND message.deletedAt IS NULL
		  AND message.messageType = shinhan.fibri.ieum.common.chat.domain.MessageType.user
		  AND message.content IS NOT NULL
		  AND (
			notice.createdAt < :cursorCreatedAt
			OR (notice.createdAt = :cursorCreatedAt AND notice.id < :cursorNoticeId)
		  )
		ORDER BY notice.createdAt DESC, notice.id DESC
		""")
	List<ChatNotice> findVisibleBeforeCursor(
		@Param("roomId") Long roomId,
		@Param("userId") Long userId,
		@Param("cursorCreatedAt") OffsetDateTime cursorCreatedAt,
		@Param("cursorNoticeId") Long cursorNoticeId,
		Pageable pageable
	);

	@Query("""
		SELECT notice
		FROM ChatNotice notice
		JOIN FETCH notice.room room
		JOIN FETCH notice.message message
		JOIN FETCH message.sender
		LEFT JOIN FETCH message.replyTo replyTo
		LEFT JOIN FETCH replyTo.sender
		JOIN ChatMember member ON member.room = room AND member.user.id = :userId
		WHERE room.id = :roomId
		  AND notice.id = :noticeId
		  AND member.leftAt IS NULL
		  AND message.id > member.visibleAfterMessageId
		  AND message.deletedAt IS NULL
		  AND message.messageType = shinhan.fibri.ieum.common.chat.domain.MessageType.user
		  AND message.content IS NOT NULL
		""")
	Optional<ChatNotice> findVisibleByIdAndRoomId(
		@Param("noticeId") Long noticeId,
		@Param("roomId") Long roomId,
		@Param("userId") Long userId
	);

	@Query("""
		SELECT notice
		FROM ChatNotice notice
		JOIN FETCH notice.room room
		JOIN FETCH notice.message message
		JOIN FETCH message.sender
		LEFT JOIN FETCH message.replyTo replyTo
		LEFT JOIN FETCH replyTo.sender
		JOIN ChatMember member ON member.room = room AND member.user.id = :userId
		WHERE room.id = :roomId
		  AND message.id = :messageId
		  AND member.leftAt IS NULL
		  AND message.id > member.visibleAfterMessageId
		  AND message.deletedAt IS NULL
		  AND message.messageType = shinhan.fibri.ieum.common.chat.domain.MessageType.user
		  AND message.content IS NOT NULL
		""")
	Optional<ChatNotice> findVisibleByRoomIdAndMessageId(
		@Param("roomId") Long roomId,
		@Param("messageId") Long messageId,
		@Param("userId") Long userId
	);
}
