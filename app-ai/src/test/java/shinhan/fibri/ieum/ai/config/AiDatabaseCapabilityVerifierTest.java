package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import shinhan.fibri.ieum.ai.config.AiDatabaseCapabilityVerifier.DatabaseCapabilities;

class AiDatabaseCapabilityVerifierTest {

	private final AiDatabaseCapabilityVerifier verifier = new AiDatabaseCapabilityVerifier(
		null,
		new AiDatabaseProperties(768, Set.of("vector", "postgis", "pgcrypto")),
		null
	);

	@Test
	void acceptsPostgresql16WithRequiredExtensionsAndVectorDimension() {
		assertThatCode(() -> verifier.validate(new DatabaseCapabilities(
			"PostgreSQL", 160000, Set.of("vector", "postgis", "pgcrypto"), 768
		))).doesNotThrowAnyException();
	}

	@Test
	void rejectsNonPostgresql16Database() {
		assertThatThrownBy(() -> verifier.validate(new DatabaseCapabilities(
			"H2", 0, Set.of(), 0
		)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("PostgreSQL 16");
	}

	@Test
	void rejectsMissingVectorExtension() {
		assertThatThrownBy(() -> verifier.validate(new DatabaseCapabilities(
			"PostgreSQL", 160000, Set.of("postgis"), 768
		)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("vector");
	}

	@Test
	void rejectsMissingPgcryptoExtension() {
		assertThatThrownBy(() -> verifier.validate(new DatabaseCapabilities(
			"PostgreSQL", 160000, Set.of("vector", "postgis"), 768
		)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("pgcrypto");
	}

	@Test
	void rejectsMissingExtensionsBeforeProbingVectorDimensions() {
		JdbcClient jdbc = mock(JdbcClient.class);
		JdbcClient.StatementSpec productNameStatement = mock(JdbcClient.StatementSpec.class);
		JdbcClient.MappedQuerySpec<String> productNameQuery = mock(JdbcClient.MappedQuerySpec.class);
		JdbcClient.StatementSpec versionStatement = mock(JdbcClient.StatementSpec.class);
		JdbcClient.MappedQuerySpec<String> versionQuery = mock(JdbcClient.MappedQuerySpec.class);
		JdbcClient.StatementSpec extensionsStatement = mock(JdbcClient.StatementSpec.class);
		JdbcClient.MappedQuerySpec<String> extensionsQuery = mock(JdbcClient.MappedQuerySpec.class);
		when(jdbc.sql("SELECT split_part(version(), ' ', 1)")).thenReturn(productNameStatement);
		when(productNameStatement.query(String.class)).thenReturn(productNameQuery);
		when(productNameQuery.single()).thenReturn("PostgreSQL");
		when(jdbc.sql("SHOW server_version_num")).thenReturn(versionStatement);
		when(versionStatement.query(String.class)).thenReturn(versionQuery);
		when(versionQuery.single()).thenReturn("160000");
		when(jdbc.sql("SELECT extname FROM pg_extension")).thenReturn(extensionsStatement);
		when(extensionsStatement.query(String.class)).thenReturn(extensionsQuery);
		when(extensionsQuery.list()).thenReturn(java.util.List.of("vector", "postgis"));
		AiDatabaseCapabilityVerifier verifier = new AiDatabaseCapabilityVerifier(
			jdbc,
			new AiDatabaseProperties(768, Set.of("vector", "postgis", "pgcrypto")),
			null
		);

		assertThatThrownBy(verifier::loadCapabilities)
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("pgcrypto");
		verify(jdbc, never()).sql(contains("CREATE TEMP TABLE ai_vector_dimension_probe"));
	}

	@Test
	void rejectsUnexpectedVectorDimension() {
		assertThatThrownBy(() -> verifier.validate(new DatabaseCapabilities(
			"PostgreSQL", 160000, Set.of("vector", "postgis", "pgcrypto"), 1536
		)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("768");
	}
}
