package shinhan.fibri.ieum.main.admin.knowledge.exception;

public class KnowledgeCandidateConcurrentlyChangedException extends RuntimeException {

	public KnowledgeCandidateConcurrentlyChangedException() {
		super("Knowledge relation candidate was concurrently changed");
	}
}
