package shinhan.fibri.ieum.main.file.dto;

import java.io.InputStream;

public record FileStreamResponse(
	String contentType,
	Long contentLength,
	BodySupplier bodySupplier
) {

	public InputStream body() {
		return bodySupplier.open();
	}

	@FunctionalInterface
	public interface BodySupplier {
		InputStream open();
	}
}
