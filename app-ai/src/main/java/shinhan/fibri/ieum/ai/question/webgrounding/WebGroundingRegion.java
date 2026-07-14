package shinhan.fibri.ieum.ai.question.webgrounding;

public record WebGroundingRegion(
	String country,
	String sido,
	String sigungu
) {

	private static final String KOREA_COUNTRY_CODE = "KR";

	public WebGroundingRegion {
		country = normalize(country);
		sido = normalize(sido);
		sigungu = normalize(sigungu);
		if (country != null && !KOREA_COUNTRY_CODE.equals(country)) {
			throw new IllegalArgumentException("country must be KR");
		}
		if (country == null && (sido != null || sigungu != null)) {
			throw new IllegalArgumentException("country is required when a coarse region is present");
		}
		if (sigungu != null && sido == null) {
			throw new IllegalArgumentException("sido is required when sigungu is present");
		}
	}

	public static WebGroundingRegion empty() {
		return new WebGroundingRegion(null, null, null);
	}

	public static WebGroundingRegion korea(String sido, String sigungu) {
		return new WebGroundingRegion(KOREA_COUNTRY_CODE, sido, sigungu);
	}

	public boolean isEmpty() {
		return country == null;
	}

	private static String normalize(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}
}
