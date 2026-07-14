package shinhan.fibri.ieum.main.file.service;

import java.util.Locale;
import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;

public enum FileVariant {
	ORIGIN,
	DISPLAY,
	THUMB;

	public static FileVariant from(String value) {
		if (value == null || value.isBlank()) {
			return DISPLAY;
		}
		return switch (value.trim().toLowerCase(Locale.ROOT)) {
			case "origin" -> ORIGIN;
			case "display" -> DISPLAY;
			case "thumb" -> THUMB;
			default -> throw new InvalidFileRequestException("Unsupported file variant");
		};
	}
}
