package shinhan.fibri.ieum.ai.knowledge.accepted;

import java.util.Objects;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.StoredLocationSnapshot;

public record AcceptedAnswerKnowledgeSnapshot(
	String questionTitle,
	String questionBody,
	String acceptedAnswer,
	StoredLocationSnapshot location,
	GeoScope persistedGeoScope
) {

	public AcceptedAnswerKnowledgeSnapshot {
		Objects.requireNonNull(location, "location must not be null");
	}
}
