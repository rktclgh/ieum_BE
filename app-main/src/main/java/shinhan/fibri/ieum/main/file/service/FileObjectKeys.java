package shinhan.fibri.ieum.main.file.service;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import shinhan.fibri.ieum.main.file.exception.InvalidFileRequestException;

public final class FileObjectKeys {

	private static final Pattern PURPOSE_PATTERN = Pattern.compile("[a-z][a-z0-9_-]{0,39}");
	private static final Set<String> ALLOWED_PURPOSES = Set.of("profile", "meeting", "chat", "question", "answer");

	private FileObjectKeys() {
	}

	public static String tmpOriginKey(String tmpPrefix, Long userId, String purpose, UUID fileId, String contentType) {
		return originKey(tmpPrefix, userId, purpose, fileId, contentType);
	}

	public static String finalOriginKey(String finalPrefix, Long userId, String purpose, UUID fileId, String contentType) {
		return originKey(finalPrefix, userId, purpose, fileId, contentType);
	}

	public static String promoteTmpOriginKey(String tmpPrefix, String finalPrefix, String tmpOriginKey) {
		String cleanedTmpPrefix = cleanPrefix(tmpPrefix);
		String cleanedFinalPrefix = cleanPrefix(finalPrefix);
		if (tmpOriginKey == null || tmpOriginKey.isBlank()) {
			throw new InvalidFileRequestException("tmp origin key is required");
		}
		String cleanedTmpKey = tmpOriginKey.trim();
		String prefix = cleanedTmpPrefix + "/";
		if (!cleanedTmpKey.startsWith(prefix)) {
			throw new InvalidFileRequestException("Invalid tmp origin key");
		}
		return cleanedFinalPrefix + "/" + cleanedTmpKey.substring(prefix.length());
	}

	public static String variantKey(String originKey, FileVariant variant) {
		if (variant == FileVariant.ORIGIN) {
			return originKey;
		}
		int directoryEnd = originKey.lastIndexOf('/');
		if (directoryEnd < 0) {
			throw new InvalidFileRequestException("Invalid origin key");
		}
		String directory = originKey.substring(0, directoryEnd + 1);
		return switch (variant) {
			case DISPLAY -> directory + "display.webp";
			case THUMB -> directory + "thumb.webp";
			case ORIGIN -> originKey;
		};
	}

	static String extension(String contentType) {
		return switch (normalize(contentType)) {
			case "image/jpeg" -> "jpg";
			case "image/png" -> "png";
			default -> throw new InvalidFileRequestException("Only jpeg and png images are supported");
		};
	}

	static boolean isSupportedOriginContentType(String contentType) {
		String normalized = normalize(contentType);
		return "image/jpeg".equals(normalized) || "image/png".equals(normalized);
	}

	static String normalize(String contentType) {
		if (contentType == null) {
			throw new InvalidFileRequestException("contentType is required");
		}
		return contentType.trim().toLowerCase(Locale.ROOT);
	}

	private static String originKey(String prefix, Long userId, String purpose, UUID fileId, String contentType) {
		if (prefix == null || prefix.isBlank()) {
			throw new InvalidFileRequestException("file prefix is required");
		}
		if (userId == null) {
			throw new InvalidFileRequestException("userId is required");
		}
		String cleanedPurpose = cleanPurpose(purpose);
		if (fileId == null) {
			throw new InvalidFileRequestException("fileId is required");
		}
		return cleanPrefix(prefix) + "/" + userId + "/" + cleanedPurpose + "/" + fileId + "/original." + extension(contentType);
	}

	private static String cleanPurpose(String purpose) {
		if (purpose == null) {
			throw new InvalidFileRequestException("purpose is required");
		}
		String cleaned = purpose.trim().toLowerCase(Locale.ROOT);
		if (!PURPOSE_PATTERN.matcher(cleaned).matches() || !ALLOWED_PURPOSES.contains(cleaned)) {
			throw new InvalidFileRequestException("Invalid file purpose");
		}
		return cleaned;
	}

	private static String cleanPrefix(String prefix) {
		String cleaned = prefix.trim();
		while (cleaned.startsWith("/")) {
			cleaned = cleaned.substring(1);
		}
		while (cleaned.endsWith("/")) {
			cleaned = cleaned.substring(0, cleaned.length() - 1);
		}
		if (cleaned.isBlank()) {
			throw new InvalidFileRequestException("file prefix is required");
		}
		return cleaned;
	}
}
