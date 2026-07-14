package shinhan.fibri.ieum.ai.question.analysis;

public final class HighRiskDomainResolver {

	private HighRiskDomainResolver() {
	}

	public static boolean isHighRisk(String domain) {
		return QuestionDomainResolver.resolve(domain).highRisk();
	}
}
