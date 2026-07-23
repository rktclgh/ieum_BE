package shinhan.fibri.ieum.main.admin.content.domain;

import java.util.Locale;
import shinhan.fibri.ieum.main.admin.content.exception.UnsupportedContentTypeException;

public enum AdminContentType {
	QUESTION("question", "DELETE QUESTION "),
	MEETING("meeting", "DELETE MEETING ");

	private final String pathValue;
	private final String confirmationPrefix;

	AdminContentType(String pathValue, String confirmationPrefix) {
		this.pathValue = pathValue;
		this.confirmationPrefix = confirmationPrefix;
	}

	public String pathValue() {
		return pathValue;
	}

	public String expectedConfirmationToken(Long id) {
		return confirmationPrefix + id;
	}

	public static AdminContentType fromPath(String type) {
		if (type == null) {
			throw new UnsupportedContentTypeException(null);
		}
		return switch (type.toLowerCase(Locale.ROOT)) {
			case "question" -> QUESTION;
			case "meeting" -> MEETING;
			default -> throw new UnsupportedContentTypeException(type);
		};
	}
}
