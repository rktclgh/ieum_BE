package shinhan.fibri.ieum.ai.knowledge.relations;

import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;

record KnowledgeRelationCandidate(
	String subject,
	KnowledgeRelationPredicate predicate,
	String object,
	double confidence,
	String evidenceExcerpt
) {
}
