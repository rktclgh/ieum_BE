package shinhan.fibri.ieum.ai.report.service;

public record VerifiedReportEvidenceImage(String contentType, byte[] bytes) {

	public VerifiedReportEvidenceImage {
		if (!"image/webp".equals(contentType)) {
			throw new IllegalArgumentException("contentType must be image/webp");
		}
		if (bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("bytes must not be empty");
		}
		bytes = bytes.clone();
	}

	@Override
	public byte[] bytes() {
		return bytes.clone();
	}

	public int byteSize() {
		return bytes.length;
	}
}
