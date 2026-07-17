package shinhan.fibri.ieum.main.translation.service;

public class TranslationAuthenticationRequiredException extends RuntimeException {

	public TranslationAuthenticationRequiredException() {
		super("Authentication is required to translate text");
	}
}
