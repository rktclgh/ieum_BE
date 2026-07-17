package shinhan.fibri.ieum.main.translation.service;

public interface TranslationRateLimiter {

	boolean tryAcquire(Long userId);
}
