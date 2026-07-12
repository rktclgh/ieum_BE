package shinhan.fibri.ieum.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;

public final class CanonicalPostgresDataSource {

	private CanonicalPostgresDataSource() {
	}

	public static void recreateAndRegister(DynamicPropertyRegistry registry, String databaseName) {
		CanonicalPostgresDatabase.recreateWithSchema(databaseName);
		registry.add("spring.datasource.url", () -> CanonicalPostgresContainer.jdbcUrl(databaseName));
		registry.add("spring.datasource.username", CanonicalPostgresContainer::username);
		registry.add("spring.datasource.password", CanonicalPostgresContainer::password);
		registry.add("spring.datasource.driver-class-name", CanonicalPostgresContainer::driverClassName);
		registry.add("spring.sql.init.mode", () -> "never");
	}
}
