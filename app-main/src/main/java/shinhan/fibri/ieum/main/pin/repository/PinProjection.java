package shinhan.fibri.ieum.main.pin.repository;

import java.time.Instant;
import java.util.UUID;

public interface PinProjection {

	Long getPinId();

	String getPinType();

	Long getTargetId();

	String getTitle();

	UUID getThumbnailFileId();

	Double getLatitude();

	Double getLongitude();

	Boolean getMine();

	Instant getCreatedAt();
}
