package shinhan.fibri.ieum.main.file.exception;

public class FileNotFoundException extends RuntimeException {

	public FileNotFoundException() {
		super("File not found");
	}
}
