package shinhan.fibri.ieum.ai.knowledge.packageimport;

import java.util.Objects;

public final class KnowledgeSeedImportException extends RuntimeException {

	private final Code code;

	public KnowledgeSeedImportException(Code code) {
		super(message(code));
		this.code = Objects.requireNonNull(code, "code must not be null");
	}

	public Code code() {
		return code;
	}

	private static String message(Code code) {
		return switch (Objects.requireNonNull(code, "code must not be null")) {
			case PACKAGE_VERSION_HASH_CONFLICT -> "knowledge seed package version has a different manifest hash";
			case PACKAGE_PARTIAL_STATE -> "knowledge seed package has an incomplete or mismatched stored graph";
		};
	}

	public enum Code {
		PACKAGE_VERSION_HASH_CONFLICT,
		PACKAGE_PARTIAL_STATE
	}
}
