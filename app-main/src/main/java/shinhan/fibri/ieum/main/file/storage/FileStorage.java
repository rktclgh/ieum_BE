package shinhan.fibri.ieum.main.file.storage;

import java.net.URI;
import java.time.Duration;

public interface FileStorage {

	URI createPresignedPutUrl(String key, String contentType, Long sizeBytes, Duration ttl);

	URI createPresignedGetUrl(String key, Duration ttl);

	FileObjectMetadata head(String key);

	StoredFileStream get(String key);

	void put(String key, String contentType, byte[] bytes);

	void delete(String key);
}
