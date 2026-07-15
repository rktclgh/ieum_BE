package shinhan.fibri.ieum.main.admin.content.exception;

public class ContentNotFoundException extends RuntimeException {

	public ContentNotFoundException() {
		super("Content not found");
	}
}
