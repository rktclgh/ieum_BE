package shinhan.fibri.ieum.main.place.exception;

public class PlaceProviderBusyException extends RuntimeException {

	public PlaceProviderBusyException() {
		super("Place provider is busy");
	}
}
