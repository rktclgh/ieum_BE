package shinhan.fibri.ieum.main.pin.repository;

import java.time.OffsetDateTime;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;

public interface PinWriter {

	Long create(Long authorId, PinType type, LocationSnapshot location);

	@Deprecated
	default Long create(Long authorId, PinType type, double latitude, double longitude) {
		throw new UnsupportedOperationException("Location snapshot is required");
	}

	void softDelete(Long pinId, OffsetDateTime deletedAt);
}
