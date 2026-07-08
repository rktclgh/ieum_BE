package shinhan.fibri.ieum.main.file.dto;

public record FilePresignRequest(
	String purpose,
	String contentType,
	Long sizeBytes
) {
}
