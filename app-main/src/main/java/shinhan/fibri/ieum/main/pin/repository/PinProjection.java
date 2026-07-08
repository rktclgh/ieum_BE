package shinhan.fibri.ieum.main.pin.repository;

import java.time.Instant;
import java.util.UUID;

public interface PinProjection {

	Long getPinId();

	String getPinType();

	String getTitle();

	UUID getThumbnailFileId();

	Double getLatitude();

	Double getLongitude();

	Long getAuthorId();

	Boolean getMine();

	Instant getCreatedAt();
}
