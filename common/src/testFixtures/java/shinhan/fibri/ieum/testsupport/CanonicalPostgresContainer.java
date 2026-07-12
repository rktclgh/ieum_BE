package shinhan.fibri.ieum.testsupport;

import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.utility.DockerImageName;

public final class CanonicalPostgresContainer {

	private static final String IMAGE_NAME = "ieum-canonical-postgres:pg16-vector-postgis";
	private static final String DEFAULT_DATABASE = "ieum_canonical_test";
	private static final String USERNAME = "postgres";
	private static final String PASSWORD = "postgres";
	private static final Pattern DATABASE_NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_]{2,62}");
	private static final PostgreSQLContainer<?> POSTGRES = createContainer();

	private CanonicalPostgresContainer() {
	}

	public static DataSource dataSource() {
		return dataSource(DEFAULT_DATABASE);
	}

	public static DataSource dataSource(String databaseName) {
		validateDatabaseName(databaseName);
		PGSimpleDataSource dataSource = new PGSimpleDataSource();
		dataSource.setURL(jdbcUrl(databaseName));
		dataSource.setUser(username());
		dataSource.setPassword(password());
		return dataSource;
	}

	public static String jdbcUrl() {
		return jdbcUrl(DEFAULT_DATABASE);
	}

	public static String jdbcUrl(String databaseName) {
		validateDatabaseName(databaseName);
		ensureStarted();
		return "jdbc:postgresql://%s:%d/%s".formatted(
			POSTGRES.getHost(),
			POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
			databaseName);
	}

	public static void recreateDatabase(String databaseName) {
		validateDatabaseName(databaseName);
		ensureStarted();
		JdbcClient admin = JdbcClient.create(dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)").update();
		admin.sql("CREATE DATABASE " + databaseName).update();
	}

	public static PostgreSQLContainer<?> instance() {
		ensureStarted();
		return POSTGRES;
	}

	public static String username() {
		return POSTGRES.getUsername();
	}

	public static String password() {
		return POSTGRES.getPassword();
	}

	public static String driverClassName() {
		return POSTGRES.getDriverClassName();
	}

	static void validateDatabaseName(String databaseName) {
		if (databaseName == null || !DATABASE_NAME_PATTERN.matcher(databaseName).matches()) {
			throw new IllegalArgumentException("Invalid PostgreSQL database name: " + databaseName);
		}
	}

	private static PostgreSQLContainer<?> createContainer() {
		DockerImageName imageName = DockerImageName.parse(builtImageName()).asCompatibleSubstituteFor("postgres");
		PostgreSQLContainer<?> container = new PostgreSQLContainer<>(imageName)
			.withDatabaseName(DEFAULT_DATABASE)
			.withUsername(USERNAME)
			.withPassword(PASSWORD);
		container.start();
		return container;
	}

	private static String builtImageName() {
		return imageFromDockerfile().get();
	}

	private static ImageFromDockerfile imageFromDockerfile() {
		return new ImageFromDockerfile(IMAGE_NAME, false)
			.withFileFromClasspath("Dockerfile", "test-support/postgres-ai/Dockerfile");
	}

	private static void ensureStarted() {
		if (!POSTGRES.isRunning()) {
			POSTGRES.start();
		}
	}
}
