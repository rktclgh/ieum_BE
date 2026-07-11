package shinhan.fibri.ieum.ai.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
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
	void rejectsUnexpectedVectorDimension() {
		assertThatThrownBy(() -> verifier.validate(new DatabaseCapabilities(
			"PostgreSQL", 160000, Set.of("vector", "postgis", "pgcrypto"), 1536
		)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("768");
	}
}
