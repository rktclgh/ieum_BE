package shinhan.fibri.ieum.ai.report.service;

public record ReportReviewModelPromptImage(long messageId, String contentType, byte[] bytes) {

	public ReportReviewModelPromptImage {
		if (messageId < 1 || contentType == null || contentType.isBlank() || bytes == null || bytes.length == 0) {
			throw new IllegalArgumentException("report review model prompt image must be valid");
		}
		bytes = bytes.clone();
	}

	@Override
	public byte[] bytes() {
		return bytes.clone();
	}
}
