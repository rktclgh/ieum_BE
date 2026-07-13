package shinhan.fibri.ieum;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;
import org.springframework.boot.WebApplicationType;
import shinhan.fibri.ieum.ai.knowledge.packageimport.KnowledgeImportApplicationConfiguration;

enum AiApplicationMode {

	SERVER("server", AiApplication.class, WebApplicationType.SERVLET, false),
	KNOWLEDGE_IMPORT(
		"knowledge-import",
		KnowledgeImportApplicationConfiguration.class,
		WebApplicationType.NONE,
		true
	);

	private final String propertyValue;
	private final Class<?> applicationSource;
	private final WebApplicationType webApplicationType;
	private final boolean oneShot;

	AiApplicationMode(
		String propertyValue,
		Class<?> applicationSource,
		WebApplicationType webApplicationType,
		boolean oneShot
	) {
		this.propertyValue = propertyValue;
		this.applicationSource = applicationSource;
		this.webApplicationType = webApplicationType;
		this.oneShot = oneShot;
	}

	static AiApplicationMode from(String value) {
		if (value == null) {
			return SERVER;
		}
		return Arrays.stream(values())
			.filter(mode -> mode.propertyValue.equals(value))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException(
				"app.ai.mode must be one of [%s]; was: %s".formatted(
					Arrays.stream(values())
						.map(mode -> mode.propertyValue)
						.collect(Collectors.joining(", ")),
					value
				)
			));
	}

	Class<?> applicationSource() {
		return applicationSource;
	}

	WebApplicationType webApplicationType() {
		return webApplicationType;
	}

	String webApplicationTypePropertyValue() {
		return webApplicationType.name().toLowerCase(Locale.ROOT);
	}

	boolean oneShot() {
		return oneShot;
	}
}
