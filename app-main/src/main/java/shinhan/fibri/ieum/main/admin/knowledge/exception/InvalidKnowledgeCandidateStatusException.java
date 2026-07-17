package shinhan.fibri.ieum.main.admin.knowledge.exception;

public class InvalidKnowledgeCandidateStatusException extends RuntimeException {

	public InvalidKnowledgeCandidateStatusException() {
		super("Knowledge candidate status must be pending, approved, rejected, or invalidated");
	}
}
