package shinhan.fibri.ieum.ai.knowledge.packageimport;

import java.util.List;
import java.util.Objects;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;

public record PreparedKnowledgeSeedPackage(
	KnowledgeSeedPackage seedPackage,
	List<PreparedSource> sources
) {

	public PreparedKnowledgeSeedPackage {
		Objects.requireNonNull(seedPackage, "seedPackage must not be null");
		Objects.requireNonNull(sources, "sources must not be null");
		sources = List.copyOf(sources);
		if (sources.size() != seedPackage.sources().size()) {
			throw new IllegalArgumentException("prepared sources must match seed package sources");
		}
		for (int index = 0; index < sources.size(); index++) {
			if (!sources.get(index).source().equals(seedPackage.sources().get(index))) {
				throw new IllegalArgumentException("prepared sources must preserve seed package order");
			}
		}
	}

	public record PreparedSource(
		KnowledgeSeedPackage.Source source,
		GeminiEmbedding embedding
	) {

		public PreparedSource {
			Objects.requireNonNull(source, "source must not be null");
			Objects.requireNonNull(embedding, "embedding must not be null");
		}
	}
}
