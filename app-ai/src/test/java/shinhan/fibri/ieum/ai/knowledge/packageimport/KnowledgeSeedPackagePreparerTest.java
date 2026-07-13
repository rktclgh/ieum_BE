package shinhan.fibri.ieum.ai.knowledge.packageimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingGateway;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbeddingUnavailableException;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbedder;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbeddingTextFormatter;

class KnowledgeSeedPackagePreparerTest {

	private static final String RESOURCE = "/knowledge/korea_long_stay_seed_v0.2.json";

	@Test
	void preparesEveryCanonicalSourceSequentiallyInManifestOrder() throws IOException {
		KnowledgeSeedPackage seedPackage = canonicalPackage();
		CapturingGateway gateway = new CapturingGateway();
		KnowledgeSeedPackagePreparer preparer = preparer(gateway);

		PreparedKnowledgeSeedPackage prepared = preparer.prepare(seedPackage);

		assertThat(gateway.formattedTexts).hasSize(20);
		assertThat(gateway.maxConcurrentCalls).isOne();
		assertThat(prepared.seedPackage()).isSameAs(seedPackage);
		assertThat(prepared.sources()).hasSize(20);
		for (int index = 0; index < seedPackage.sources().size(); index++) {
			KnowledgeSeedPackage.Source source = seedPackage.sources().get(index);
			PreparedKnowledgeSeedPackage.PreparedSource preparedSource = prepared.sources().get(index);
			assertThat(preparedSource.source()).isSameAs(source);
			assertThat(preparedSource.embedding().model()).isEqualTo("gemini-embedding-2");
			assertThat(preparedSource.embedding().values().getFirst()).isEqualTo(index / 100.0f);
			assertThat(gateway.formattedTexts.get(index)).isEqualTo(
				"title: " + source.displayName() + " | text: " + source.chunks().getFirst().text()
			);
		}
		assertThatThrownBy(() -> prepared.sources().clear())
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void stopsImmediatelyWhenOneEmbeddingFails() throws IOException {
		CapturingGateway gateway = new CapturingGateway();
		gateway.failAtCall = 3;
		KnowledgeSeedPackagePreparer preparer = preparer(gateway);

		assertThatThrownBy(() -> preparer.prepare(canonicalPackage()))
			.isSameAs(gateway.failure)
			.isInstanceOf(GeminiEmbeddingUnavailableException.class);
		assertThat(gateway.formattedTexts).hasSize(3);
	}

	private KnowledgeSeedPackagePreparer preparer(GeminiEmbeddingGateway gateway) {
		return new KnowledgeSeedPackagePreparer(new KnowledgeDocumentEmbedder(
			new KnowledgeDocumentEmbeddingTextFormatter(),
			gateway
		));
	}

	private KnowledgeSeedPackage canonicalPackage() throws IOException {
		try (InputStream input = getClass().getResourceAsStream(RESOURCE)) {
			if (input == null) {
				throw new IOException("Missing canonical resource " + RESOURCE);
			}
			return new KnowledgeSeedPackageParser(new ObjectMapper()).parse(input);
		}
	}

	private static List<Float> embedding(float firstValue) {
		List<Float> values = new ArrayList<>(java.util.Collections.nCopies(GeminiEmbedding.DIMENSIONS, 0f));
		values.set(0, firstValue);
		return values;
	}

	private static final class CapturingGateway implements GeminiEmbeddingGateway {

		private final List<String> formattedTexts = new ArrayList<>();
		private final GeminiEmbeddingUnavailableException failure = new GeminiEmbeddingUnavailableException();
		private int failAtCall = -1;
		private int concurrentCalls;
		private int maxConcurrentCalls;

		@Override
		public GeminiEmbedding embed(String formattedText) {
			concurrentCalls++;
			maxConcurrentCalls = Math.max(maxConcurrentCalls, concurrentCalls);
			try {
				formattedTexts.add(formattedText);
				if (formattedTexts.size() == failAtCall) {
					throw failure;
				}
				return new GeminiEmbedding(
					GeminiEmbedding.MODEL,
					embedding((formattedTexts.size() - 1) / 100.0f)
				);
			}
			finally {
				concurrentCalls--;
			}
		}
	}
}
