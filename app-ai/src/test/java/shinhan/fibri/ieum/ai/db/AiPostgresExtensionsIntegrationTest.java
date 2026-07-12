package shinhan.fibri.ieum.ai.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URL;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

class AiPostgresExtensionsIntegrationTest {

	@Test
	void exposesCanonicalSchemaOnTheTestClasspath() {
		URL schema = getClass().getClassLoader().getResource("canonical-db/schema.sql");

		assertThat(schema).isNotNull();
	}

	@Test
	void providesVectorPostgisAndPgcryptoOnPostgresql16() {
		String databaseName = "ieum_ai_extensions";
		CanonicalPostgresContainer.recreateDatabase(databaseName);

		JdbcClient jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(databaseName));
		jdbc.sql("CREATE EXTENSION IF NOT EXISTS vector").update();
		jdbc.sql("CREATE EXTENSION IF NOT EXISTS postgis").update();
		jdbc.sql("CREATE EXTENSION IF NOT EXISTS pgcrypto").update();

		Map<String, String> versions = jdbc.sql("""
			SELECT extname, extversion
			FROM pg_extension
			WHERE extname IN ('vector', 'postgis', 'pgcrypto')
			""")
			.query((resultSet, rowNumber) -> Map.entry(resultSet.getString(1), resultSet.getString(2)))
			.list()
			.stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

		assertThat(jdbc.sql("SHOW server_version_num").query(Integer.class).single()).isGreaterThanOrEqualTo(160000);
		assertThat(versions).containsKeys("vector", "postgis", "pgcrypto");
	}
}
