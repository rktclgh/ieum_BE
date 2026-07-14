package shinhan.fibri.ieum.ai.question.generation;

public record LocalAnswerRegion(
	String country,
	String sido,
	String sigungu,
	String eupMyeonDong
) {

	private static final String KOREA_COUNTRY_CODE = "KR";

	public LocalAnswerRegion {
		country = normalize(country);
		sido = normalize(sido);
		sigungu = normalize(sigungu);
		eupMyeonDong = normalize(eupMyeonDong);
		if (country != null && !KOREA_COUNTRY_CODE.equals(country)) {
			throw new IllegalArgumentException("country must be KR");
		}
		if (country == null && (sido != null || sigungu != null || eupMyeonDong != null)) {
			throw new IllegalArgumentException("country is required when a coarse region is present");
		}
	}

	public static LocalAnswerRegion empty() {
		return new LocalAnswerRegion(null, null, null, null);
	}

	public static LocalAnswerRegion korea(String sido, String sigungu, String eupMyeonDong) {
		return new LocalAnswerRegion(KOREA_COUNTRY_CODE, sido, sigungu, eupMyeonDong);
	}

	public boolean isEmpty() {
		return country == null;
	}

	private static String normalize(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
