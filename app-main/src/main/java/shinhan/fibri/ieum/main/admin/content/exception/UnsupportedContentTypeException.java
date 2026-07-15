package shinhan.fibri.ieum.main.admin.content.exception;

public class UnsupportedContentTypeException extends RuntimeException {

	public UnsupportedContentTypeException(String type) {
		super("Content type is not implemented: " + type);
	}
}
