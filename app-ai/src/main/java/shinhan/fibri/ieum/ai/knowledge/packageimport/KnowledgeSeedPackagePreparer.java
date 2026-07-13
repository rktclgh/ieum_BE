package shinhan.fibri.ieum.ai.knowledge.packageimport;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import shinhan.fibri.ieum.ai.embedding.GeminiEmbedding;
import shinhan.fibri.ieum.ai.knowledge.embedding.KnowledgeDocumentEmbedder;

public final class KnowledgeSeedPackagePreparer {

	private final KnowledgeDocumentEmbedder documentEmbedder;

	public KnowledgeSeedPackagePreparer(KnowledgeDocumentEmbedder documentEmbedder) {
		this.documentEmbedder = Objects.requireNonNull(documentEmbedder, "documentEmbedder must not be null");
	}

	public PreparedKnowledgeSeedPackage prepare(KnowledgeSeedPackage seedPackage) {
		Objects.requireNonNull(seedPackage, "seedPackage must not be null");
		List<PreparedKnowledgeSeedPackage.PreparedSource> preparedSources = new ArrayList<>(
			seedPackage.sources().size()
		);
		for (KnowledgeSeedPackage.Source source : seedPackage.sources()) {
			if (source.chunks() == null || source.chunks().size() != 1) {
				throw new IllegalArgumentException("source must contain exactly one chunk");
			}
			GeminiEmbedding embedding = documentEmbedder.embed(
				source.displayName(),
				source.chunks().getFirst().text()
			);
			preparedSources.add(new PreparedKnowledgeSeedPackage.PreparedSource(source, embedding));
		}
		return new PreparedKnowledgeSeedPackage(seedPackage, preparedSources);
	}
}
