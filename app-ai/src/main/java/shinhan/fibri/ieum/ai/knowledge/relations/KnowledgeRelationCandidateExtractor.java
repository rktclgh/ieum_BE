package shinhan.fibri.ieum.ai.knowledge.relations;

import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeDocument;

public interface KnowledgeRelationCandidateExtractor {

	CandidateExtractionResult extract(AcceptedAnswerKnowledgeDocument document);
}
