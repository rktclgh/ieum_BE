package shinhan.fibri.ieum.main.admin.knowledge.exception;

public class KnowledgeCandidateNotFoundException extends RuntimeException {

	public KnowledgeCandidateNotFoundException() {
		super("Knowledge relation candidate not found");
	}
}
