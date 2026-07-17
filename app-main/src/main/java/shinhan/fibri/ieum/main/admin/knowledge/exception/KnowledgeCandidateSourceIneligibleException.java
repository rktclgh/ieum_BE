package shinhan.fibri.ieum.main.admin.knowledge.exception;

public class KnowledgeCandidateSourceIneligibleException extends RuntimeException {

	public KnowledgeCandidateSourceIneligibleException() {
		super("Knowledge relation candidate source is no longer eligible");
	}
}
