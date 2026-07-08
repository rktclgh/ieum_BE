package shinhan.fibri.ieum.main.file.storage;

import java.io.InputStream;

public record StoredFileStream(
	String key,
	String contentType,
	Long sizeBytes,
	InputStream body
) {
}
