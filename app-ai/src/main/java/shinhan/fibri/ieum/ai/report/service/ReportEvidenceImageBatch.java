package shinhan.fibri.ieum.ai.report.service;

import java.util.LinkedHashMap;
import java.util.Map;

public record ReportEvidenceImageBatch(Map<Long, VerifiedReportEvidenceImage> imagesByMessageId, long totalBytes) {

	public ReportEvidenceImageBatch {
		if (imagesByMessageId == null || totalBytes < 0) {
			throw new IllegalArgumentException("image batch must be valid");
		}
		Map<Long, VerifiedReportEvidenceImage> copiedImages = new LinkedHashMap<>();
		long actualTotal = 0;
		for (Map.Entry<Long, VerifiedReportEvidenceImage> entry : imagesByMessageId.entrySet()) {
			if (entry.getKey() == null || entry.getKey() < 1 || entry.getValue() == null) {
				throw new IllegalArgumentException("image batch must contain valid message images");
			}
			copiedImages.put(entry.getKey(), entry.getValue());
			actualTotal += entry.getValue().byteSize();
		}
		if (actualTotal != totalBytes) {
			throw new IllegalArgumentException("totalBytes must match the image bytes");
		}
		imagesByMessageId = Map.copyOf(copiedImages);
	}
}
