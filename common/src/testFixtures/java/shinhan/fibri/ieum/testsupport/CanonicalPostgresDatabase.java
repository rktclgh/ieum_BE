package shinhan.fibri.ieum.testsupport;

public final class CanonicalPostgresDatabase {

	private CanonicalPostgresDatabase() {
	}

	public static void recreateWithSchema(String databaseName) {
		CanonicalPostgresContainer.recreateDatabase(databaseName);
		SqlScriptRunner.run(databaseName, "schema.sql", "seed_countries.sql");
	}
}
