package shinhan.fibri.ieum.main.translation.service;

public class TranslationNotAvailableException extends RuntimeException {

	public TranslationNotAvailableException() {
		super("Translation is not available for this content");
	}
}
