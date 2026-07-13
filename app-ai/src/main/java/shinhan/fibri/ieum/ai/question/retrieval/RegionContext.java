package shinhan.fibri.ieum.ai.question.retrieval;

public record RegionContext(String sido, String sigungu) {

	public RegionContext {
		sido = normalize(sido);
		sigungu = normalize(sigungu);
	}

	public static RegionContext empty() {
		return new RegionContext(null, null);
	}

	boolean hasSido() {
		return sido != null;
	}

	boolean hasSigungu() {
		return sigungu != null;
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		return value.trim();
	}
}
