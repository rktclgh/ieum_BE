package shinhan.fibri.ieum.common.chat.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
		  AND (:roomType IS NULL OR room.roomType = :roomType)
		""")
	List<ChatRoom> findActiveRoomsByUserId(
		@Param("userId") Long userId,
		@Param("roomType") RoomType roomType
	);
}
