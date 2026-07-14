package shinhan.fibri.ieum.main.place.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record PlaceResponse(
	String id,
	String name,
	String address,
	double lat,
	double lng,
	String categoryGroupName
) {
}
