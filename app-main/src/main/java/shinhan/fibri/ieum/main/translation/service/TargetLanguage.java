package shinhan.fibri.ieum.main.translation.service;

import java.util.Arrays;

public enum TargetLanguage {
	KO("ko"),
	EN("en"),
	JA("ja"),
	ZH("zh"),
	VI("vi"),
	TH("th"),
	RU("ru");

	private final String code;

	TargetLanguage(String code) {
		this.code = code;
	}

	public String code() {
		return code;
	}

	public static TargetLanguage fromCode(String code) {
		return Arrays.stream(values())
			.filter(language -> language.code.equals(code))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("Unsupported target language"));
	}
}
