package shinhan.fibri.ieum.ai.knowledge.packageimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingUnavailableException;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbedder;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbeddingTextFormatter;

class KnowledgeSeedPackageImporterTest {

	private static final String RESOURCE = "/knowledge/korea_long_stay_seed_v0.2.json";

	@Test
	void delegatesToTheAtomicStoreOnlyAfterTheCompletePackageIsPrepared() throws IOException {
		RecordingEmbeddingGateway gateway = new RecordingEmbeddingGateway();
		RecordingStore store = new RecordingStore(KnowledgeSeedImportOutcome.NO_OP, gateway);
		KnowledgeSeedPackageImporter importer = importer(gateway, store);

		KnowledgeSeedImportOutcome outcome;
		TrackingInputStream input = canonicalTrackingInput();
		try {
			outcome = importer.importPackage(input);
			assertThat(input.closed).isFalse();
		}
		finally {
			input.close();
		}

		assertThat(outcome).isEqualTo(KnowledgeSeedImportOutcome.NO_OP);
		assertThat(gateway.calls).isEqualTo(20);
		assertThat(store.calls).isOne();
		assertThat(store.embeddingCallsWhenStored).isEqualTo(20);
		assertThat(store.plan).isNotNull();
		assertThat(store.plan.sources()).hasSize(20);
		assertThat(store.plan.sources())
			.flatExtracting(KnowledgeSeedPersistencePlan.SourceRow::relations)
			.hasSize(50);
	}

	@Test
	void neverTouchesTheStoreWhenAnEmbeddingFails() throws IOException {
		RecordingEmbeddingGateway gateway = new RecordingEmbeddingGateway();
		gateway.failAtCall = 3;
		RecordingStore store = new RecordingStore(KnowledgeSeedImportOutcome.IMPORTED, gateway);
		KnowledgeSeedPackageImporter importer = importer(gateway, store);

		try (InputStream input = canonicalInput()) {
			assertThatThrownBy(() -> importer.importPackage(input))
				.isSameAs(gateway.failure)
				.isInstanceOf(GeminiEmbeddingUnavailableException.class);
		}
		assertThat(gateway.calls).isEqualTo(3);
		assertThat(store.calls).isZero();
		assertThat(store.plan).isNull();
	}

	@Test
	void neverEmbedsOrStoresAnInvalidPackage() {
		RecordingEmbeddingGateway gateway = new RecordingEmbeddingGateway();
		RecordingStore store = new RecordingStore(KnowledgeSeedImportOutcome.IMPORTED, gateway);
		KnowledgeSeedPackageImporter importer = importer(gateway, store);
		InputStream input = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));

		assertThatThrownBy(() -> importer.importPackage(input))
			.isInstanceOf(KnowledgeSeedPackageValidationException.class);
		assertThat(gateway.calls).isZero();
		assertThat(store.calls).isZero();
	}

	private KnowledgeSeedPackageImporter importer(
		GeminiEmbeddingGateway gateway,
		KnowledgeSeedPackageStore store
	) {
		ObjectMapper objectMapper = new ObjectMapper();
		return new KnowledgeSeedPackageImporter(
			new KnowledgeSeedPackageParser(objectMapper),
			new KnowledgeSeedPackagePreparer(new KnowledgeDocumentEmbedder(
				new KnowledgeDocumentEmbeddingTextFormatter(),
				gateway
			)),
			new KnowledgeSeedPersistencePlanFactory(objectMapper),
			store
		);
	}

	private InputStream canonicalInput() throws IOException {
		InputStream input = getClass().getResourceAsStream(RESOURCE);
		if (input == null) {
			throw new IOException("Missing canonical resource " + RESOURCE);
		}
		return input;
	}

	private TrackingInputStream canonicalTrackingInput() throws IOException {
		try (InputStream input = canonicalInput()) {
			return new TrackingInputStream(input.readAllBytes());
		}
	}

	private static final class RecordingEmbeddingGateway implements GeminiEmbeddingGateway {

		private final GeminiEmbeddingUnavailableException failure = new GeminiEmbeddingUnavailableException();
		private int calls;
		private int failAtCall = -1;

		@Override
		public GeminiEmbedding embed(String formattedText) {
			calls++;
			if (calls == failAtCall) {
				throw failure;
			}
			return new GeminiEmbedding(
				GeminiEmbedding.MODEL,
				Collections.nCopies(GeminiEmbedding.DIMENSIONS, calls / 100.0f)
			);
		}
	}

	private static final class RecordingStore implements KnowledgeSeedPackageStore {

		private final KnowledgeSeedImportOutcome outcome;
		private final RecordingEmbeddingGateway gateway;
		private int calls;
		private int embeddingCallsWhenStored;
		private KnowledgeSeedPersistencePlan plan;

		private RecordingStore(KnowledgeSeedImportOutcome outcome, RecordingEmbeddingGateway gateway) {
			this.outcome = outcome;
			this.gateway = gateway;
		}

		@Override
		public KnowledgeSeedImportOutcome importPlan(KnowledgeSeedPersistencePlan plan) {
			calls++;
			embeddingCallsWhenStored = gateway.calls;
			this.plan = plan;
			return outcome;
		}
	}

	private static final class TrackingInputStream extends ByteArrayInputStream {

		private boolean closed;

		private TrackingInputStream(byte[] bytes) {
			super(bytes);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}
	}
}
