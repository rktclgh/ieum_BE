package shinhan.fibri.ieum.main.chat.service;

public record ChatPushTrigger(
	long messageId,
	long roomId,
	long senderId
) {
}
