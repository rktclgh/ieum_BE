package shinhan.fibri.ieum.ai.question.retrieval;

public record GeoPoint(double latitude, double longitude) {

	public GeoPoint {
		if (!Double.isFinite(latitude) || latitude < -90.0d || latitude > 90.0d) {
			throw new IllegalArgumentException("latitude must be finite and between -90 and 90");
		}
		if (!Double.isFinite(longitude) || longitude < -180.0d || longitude > 180.0d) {
			throw new IllegalArgumentException("longitude must be finite and between -180 and 180");
		}
	}
}
