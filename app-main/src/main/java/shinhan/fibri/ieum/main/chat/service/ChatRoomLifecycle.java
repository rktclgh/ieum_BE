package shinhan.fibri.ieum.main.chat.service;

public interface ChatRoomLifecycle {

	Long createGroupRoom(Long meetingId, Long hostUserId);

	Long getOrCreateDirectRoom(Long requesterUserId, Long targetUserId);

	Long getOrCreateQuestionRoom(Long questionId, Long requesterUserId, Long targetUserId);

	void addMember(Long roomId, Long userId);

	void removeMember(Long roomId, Long userId);
}
