package shinhan.fibri.ieum.main.chat.service;

public interface ChatRoomLifecycle {

	Long createGroupRoom(Long meetingId, Long hostUserId);

	Long getOrCreateQuestionRoom(Long questionId, Long firstUserId, Long secondUserId);

	void addMember(Long roomId, Long userId);

	void removeMember(Long roomId, Long userId);

	void removeGroupMemberWithDepartureMessage(Long roomId, Long userId);

	void disbandGroupRoom(Long meetingId);
}
