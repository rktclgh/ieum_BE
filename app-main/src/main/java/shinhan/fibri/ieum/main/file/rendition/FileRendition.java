package shinhan.fibri.ieum.main.file.rendition;

import shinhan.fibri.ieum.main.file.service.FileVariant;

public record FileRendition(
	FileVariant variant,
	String contentType,
	byte[] bytes
) {
}
