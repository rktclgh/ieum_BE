package shinhan.fibri.ieum.main.translation.service;

public class TranslationRateLimitedException extends RuntimeException {

	public TranslationRateLimitedException() {
		super("Too many translation requests");
	}
}
