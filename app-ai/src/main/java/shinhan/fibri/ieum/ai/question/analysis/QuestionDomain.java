package shinhan.fibri.ieum.ai.question.analysis;

public enum QuestionDomain {
	general(false),
	digital(false),
	housing(false),
	family(false),
	education(false),
	food(false),
	shopping(false),
	transport(false),
	travel(false),
	community(false),
	culture(false),
	environment(false),
	household(false),
	public_services(false),
	immigration(true),
	legal(true),
	tax(true),
	pension(true),
	insurance(true),
	medical(true),
	finance(true),
	labor(true),
	emergency(true),
	unknown(true);

	private final boolean highRisk;

	QuestionDomain(boolean highRisk) {
		this.highRisk = highRisk;
	}

	public boolean highRisk() {
		return highRisk;
	}
}
