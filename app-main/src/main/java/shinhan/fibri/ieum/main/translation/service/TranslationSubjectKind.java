package shinhan.fibri.ieum.main.translation.service;

public enum TranslationSubjectKind {
	ANSWER("answer"),
	CHAT_MESSAGE("chat_message");

	private final String cacheKeyPart;

	TranslationSubjectKind(String cacheKeyPart) {
		this.cacheKeyPart = cacheKeyPart;
	}

	String cacheKeyPart() {
		return cacheKeyPart;
	}
}
