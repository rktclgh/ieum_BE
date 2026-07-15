package shinhan.fibri.ieum.common.chat.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.common.chat.domain.ChatMember;
import shinhan.fibri.ieum.common.chat.domain.ChatMemberId;

public interface ChatMemberRepository extends JpaRepository<ChatMember, ChatMemberId> {

	@Query("""
		SELECT member
		FROM ChatMember member
		JOIN FETCH member.room
		JOIN FETCH member.user
		WHERE member.room.id = :roomId
		  AND member.user.id = :userId
		  AND member.leftAt IS NULL
		""")
	Optional<ChatMember> findActiveByRoomIdAndUserId(
		@Param("roomId") Long roomId,
		@Param("userId") Long userId
	);

	boolean existsByRoom_IdAndUser_IdAndLeftAtIsNull(Long roomId, Long userId);

	@Query("""
		SELECT member.user.id
		FROM ChatMember member
		WHERE member.room.id = :roomId
		  AND member.leftAt IS NULL
		""")
	List<Long> findActiveUserIdsByRoomId(@Param("roomId") Long roomId);

	@Query("""
		SELECT member
		FROM ChatMember member
		JOIN FETCH member.user
		WHERE member.room.id = :roomId
		""")
	List<ChatMember> findByRoom_Id(@Param("roomId") Long roomId);

	@Query("""
		SELECT member.user.id
		FROM ChatMember member
		WHERE member.room.id = :roomId
		  AND member.user.id <> :senderId
		  AND member.leftAt IS NULL
		  AND member.notifyEnabled = true
		  AND :messageId > member.visibleAfterMessageId
		ORDER BY member.user.id
		""")
	List<Long> findPushRecipientUserIds(
		@Param("roomId") Long roomId,
		@Param("senderId") Long senderId,
		@Param("messageId") Long messageId
	);

	@Modifying
	@Query("""
		UPDATE ChatMember member
		SET member.leftAt = NULL
		WHERE member.room.id = :roomId
		  AND member.user.id <> :senderId
		  AND member.leftAt IS NOT NULL
		""")
	int restoreLeftMembersByRoomIdExceptSender(
		@Param("roomId") Long roomId,
		@Param("senderId") Long senderId
	);

	@Query("""
		SELECT member
		FROM ChatMember member
		JOIN FETCH member.room
		WHERE member.user.id = :userId
		  AND member.room.id IN :roomIds
		  AND member.leftAt IS NULL
		""")
	List<ChatMember> findActiveByUserIdAndRoomIds(
		@Param("userId") Long userId,
		@Param("roomIds") List<Long> roomIds
	);

	@Query("""
		SELECT member
		FROM ChatMember member
		JOIN FETCH member.room
		JOIN FETCH member.user
		WHERE member.room.id = :roomId
		  AND member.user.id IN :userIds
		  AND member.leftAt IS NULL
		""")
	List<ChatMember> findActiveByRoomIdAndUserIds(
		@Param("roomId") Long roomId,
		@Param("userIds") List<Long> userIds
	);

}
