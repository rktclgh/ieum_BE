package shinhan.fibri.ieum.main.pin.dto;

import java.util.List;

public record PinMapResponse(
	List<PinItem> items,
	boolean truncated
) {
}
