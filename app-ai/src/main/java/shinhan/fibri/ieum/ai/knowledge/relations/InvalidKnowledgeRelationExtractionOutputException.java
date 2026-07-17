package shinhan.fibri.ieum.ai.knowledge.relations;

public class InvalidKnowledgeRelationExtractionOutputException extends RuntimeException {

	public InvalidKnowledgeRelationExtractionOutputException(String message) {
		super(message);
	}

	public InvalidKnowledgeRelationExtractionOutputException(String message, Throwable cause) {
		super(message, cause);
	}
}
