package shinhan.fibri.ieum.common.chat.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import shinhan.fibri.ieum.common.chat.domain.ChatRoom;
import shinhan.fibri.ieum.common.chat.domain.RoomType;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

	Optional<ChatRoom> findByRoomKey(String roomKey);

	Optional<ChatRoom> findByMeetingId(Long meetingId);

	@Query("""
		SELECT room
		FROM ChatMember member
		JOIN member.room room
		WHERE member.user.id = :userId
		  AND member.leftAt IS NULL
		""")
	List<ChatRoom> findActiveRoomsByUserId(@Param("userId") Long userId);

	@Query("""
		SELECT room
		FROM ChatMember member
		JOIN member.room room
		WHERE member.user.id = :userId
		  AND member.leftAt IS NULL
		  AND room.roomType = :roomType
		""")
	List<ChatRoom> findActiveRoomsByUserIdAndRoomType(
		@Param("userId") Long userId,
		@Param("roomType") RoomType roomType
	);

	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
		UPDATE ChatRoom room
		SET room.pinnedNoticeId = NULL
		WHERE room.id = :roomId
		  AND room.pinnedNoticeId = :noticeId
		""")
	int clearPinnedNoticeIfMatches(
		@Param("roomId") Long roomId,
		@Param("noticeId") Long noticeId
	);
}
