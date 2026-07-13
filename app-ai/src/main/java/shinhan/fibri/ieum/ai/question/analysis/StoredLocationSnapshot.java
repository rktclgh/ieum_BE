package shinhan.fibri.ieum.ai.question.analysis;

public record StoredLocationSnapshot(
	double latitude,
	double longitude,
	String address,
	String detailAddress,
	String label
) {

	public StoredLocationSnapshot {
		if (!Double.isFinite(latitude) || latitude < -90.0d || latitude > 90.0d) {
			throw new IllegalArgumentException("latitude must be finite and between -90 and 90");
		}
		if (!Double.isFinite(longitude) || longitude < -180.0d || longitude > 180.0d) {
			throw new IllegalArgumentException("longitude must be finite and between -180 and 180");
		}
		if (address == null || address.isBlank()) {
			throw new IllegalArgumentException("address must not be blank");
		}
		detailAddress = detailAddress == null ? "" : detailAddress;
		label = label == null ? "" : label;
	}
}
