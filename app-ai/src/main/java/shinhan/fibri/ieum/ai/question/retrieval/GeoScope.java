package shinhan.fibri.ieum.ai.question.retrieval;

public enum GeoScope {
	general,
	regional,
	local,
	place_specific;

	static GeoScope fromDatabaseValue(String value) {
		return value == null ? null : GeoScope.valueOf(value);
	}
}
