package shinhan.fibri.ieum.main.chat.service;

public interface ChatMessageRateLimiter {

	boolean tryConsumeSend(Long userId);
}
