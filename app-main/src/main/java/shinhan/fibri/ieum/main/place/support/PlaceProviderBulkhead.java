package shinhan.fibri.ieum.main.place.support;

import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import shinhan.fibri.ieum.main.place.exception.PlaceProviderBusyException;

public class PlaceProviderBulkhead {

	private final Semaphore permits;

	public PlaceProviderBulkhead(int maxConcurrent) {
		if (maxConcurrent < 1) {
			throw new IllegalArgumentException("maxConcurrent must be positive");
		}
		this.permits = new Semaphore(maxConcurrent);
	}

	public <T> T execute(Supplier<T> operation) {
		if (!permits.tryAcquire()) {
			throw new PlaceProviderBusyException();
		}
		try {
			return operation.get();
		} finally {
			permits.release();
		}
	}
}
