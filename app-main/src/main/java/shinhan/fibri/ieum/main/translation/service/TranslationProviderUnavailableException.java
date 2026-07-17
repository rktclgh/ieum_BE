package shinhan.fibri.ieum.main.translation.service;

public class TranslationProviderUnavailableException extends RuntimeException {

	public TranslationProviderUnavailableException() {
		super("Translation provider is unavailable");
	}
}
