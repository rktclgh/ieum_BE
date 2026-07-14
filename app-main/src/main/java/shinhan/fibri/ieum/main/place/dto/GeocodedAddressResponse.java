package shinhan.fibri.ieum.main.place.dto;

public record GeocodedAddressResponse(
	String roadAddress,
	String jibunAddress,
	double lat,
	double lng
) {
}
