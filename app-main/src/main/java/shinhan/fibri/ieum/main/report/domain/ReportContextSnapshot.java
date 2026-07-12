package shinhan.fibri.ieum.main.report.domain;

import java.util.Objects;

public record ReportContextSnapshot(String json, String hash) {

	public ReportContextSnapshot {
		Objects.requireNonNull(json, "json must not be null");
		Objects.requireNonNull(hash, "hash must not be null");
	}
}
