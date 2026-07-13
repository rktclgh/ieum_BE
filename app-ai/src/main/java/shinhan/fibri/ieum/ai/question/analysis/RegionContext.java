package shinhan.fibri.ieum.ai.question.analysis;

public record RegionContext(
	String country,
	String sido,
	String sigungu,
	String eupMyeonDong,
	String place
) {

	private static final String KOREA_COUNTRY_CODE = "KR";

	public RegionContext {
		country = normalize(country);
		sido = normalize(sido);
		sigungu = normalize(sigungu);
		eupMyeonDong = normalize(eupMyeonDong);
		place = normalize(place);
		if (country != null && !KOREA_COUNTRY_CODE.equals(country)) {
			throw new IllegalArgumentException("country must be KR");
		}
		if (country == null && (sido != null || sigungu != null || eupMyeonDong != null || place != null)) {
			throw new IllegalArgumentException("country is required when a region is present");
		}
	}

	public static RegionContext empty() {
		return new RegionContext(null, null, null, null, null);
	}

	public static RegionContext korea(String sido, String sigungu, String eupMyeonDong, String place) {
		return new RegionContext(KOREA_COUNTRY_CODE, sido, sigungu, eupMyeonDong, place);
	}

	public boolean isEmpty() {
		return country == null;
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
