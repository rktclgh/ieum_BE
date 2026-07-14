package shinhan.fibri.ieum.main.place.support;

public interface PlaceRateLimiter {

	boolean tryAcquire(PlaceOperation operation, String clientKey);
}
