package shinhan.fibri.ieum.ai.config;

import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@EnableConfigurationProperties(AiDatabaseProperties.class)
public class AiDatabaseCapabilityVerifier implements ApplicationRunner, Ordered {

	private static final int MINIMUM_POSTGRESQL_VERSION = 160000;

	private final JdbcClient jdbc;
	private final AiDatabaseProperties properties;
	private final TransactionTemplate transactionTemplate;

	public AiDatabaseCapabilityVerifier(
		JdbcClient jdbc,
		AiDatabaseProperties properties,
		PlatformTransactionManager transactionManager
	) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.transactionTemplate = transactionManager == null ? null : new TransactionTemplate(transactionManager);
	}

	@Override
	public void run(ApplicationArguments args) {
		validate(loadCapabilities());
	}

	@Override
	public int getOrder() {
		return 0;
	}

	DatabaseCapabilities loadCapabilities() {
		String productName = jdbc.sql("SELECT split_part(version(), ' ', 1)")
			.query(String.class)
			.single();
		int serverVersionNumber = Integer.parseInt(jdbc.sql("SHOW server_version_num")
			.query(String.class)
			.single());
		Set<String> extensions = jdbc.sql("SELECT extname FROM pg_extension")
			.query(String.class)
			.list()
			.stream()
			.collect(Collectors.toUnmodifiableSet());
		validateDatabaseAndExtensions(productName, serverVersionNumber, extensions);
		int embeddingDimensions = loadEmbeddingDimensions();

		return new DatabaseCapabilities(productName, serverVersionNumber, extensions, embeddingDimensions);
	}

	private int loadEmbeddingDimensions() {
		if (transactionTemplate == null) {
			throw new IllegalStateException("Transaction manager is required to verify vector dimensions");
		}
		return transactionTemplate.execute(status -> {
			jdbc.sql("CREATE TEMP TABLE ai_vector_dimension_probe (embedding vector(" + properties.embeddingDimensions() + ")) ON COMMIT DROP")
				.update();
			Integer dimensions = jdbc.sql("""
					SELECT atttypmod
					FROM pg_attribute
					WHERE attrelid = 'pg_temp.ai_vector_dimension_probe'::regclass
					  AND attname = 'embedding'
					""")
				.query(Integer.class)
				.single();
			status.setRollbackOnly();
			return dimensions;
		});
	}

	void validate(DatabaseCapabilities capabilities) {
		validateDatabaseAndExtensions(
			capabilities.productName(), capabilities.serverVersionNumber(), capabilities.extensions()
		);

		if (capabilities.embeddingDimensions() != properties.embeddingDimensions()) {
			throw new IllegalStateException(
				"Expected vector embedding dimension %d but found %d".formatted(
					properties.embeddingDimensions(), capabilities.embeddingDimensions()
				)
			);
		}
	}

	private void validateDatabaseAndExtensions(
		String productName,
		int serverVersionNumber,
		Set<String> extensions
	) {
		if (!"PostgreSQL".equalsIgnoreCase(productName)
			|| serverVersionNumber < MINIMUM_POSTGRESQL_VERSION) {
			throw new IllegalStateException(
				"app-ai requires PostgreSQL 16 or newer; found %s %d".formatted(
					productName, serverVersionNumber
				)
			);
		}

		Set<String> missingExtensions = properties.requiredExtensions()
			.stream()
			.filter(requiredExtension -> !extensions.contains(requiredExtension))
			.collect(Collectors.toUnmodifiableSet());
		if (!missingExtensions.isEmpty()) {
			throw new IllegalStateException("Missing required PostgreSQL extensions: " + missingExtensions);
		}
	}

	public record DatabaseCapabilities(
		String productName,
		int serverVersionNumber,
		Set<String> extensions,
		int embeddingDimensions
	) {

		public DatabaseCapabilities {
			extensions = Set.copyOf(extensions);
		}
	}
}
