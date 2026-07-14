package shinhan.fibri.ieum.main.file.storage;

public record StoredFileObject(
	String key,
	String contentType,
	Long sizeBytes,
	byte[] bytes
) {
}
