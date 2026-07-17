package shinhan.fibri.ieum.main.translation.service;

public interface TranslationClient {

	ProviderTranslationResult translate(String text, TargetLanguage targetLanguage);
}
