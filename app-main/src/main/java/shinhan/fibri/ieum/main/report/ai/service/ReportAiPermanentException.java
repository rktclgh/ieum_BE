package shinhan.fibri.ieum.main.report.ai.service;

public class ReportAiPermanentException extends RuntimeException {

	private final String errorCode;

	public ReportAiPermanentException(String errorCode) {
		super(errorCode);
		if (errorCode == null || errorCode.isBlank() || errorCode.length() > 80) {
			throw new IllegalArgumentException("errorCode must contain 1 to 80 characters");
		}
		this.errorCode = errorCode;
	}

	public String errorCode() {
		return errorCode;
	}
}
