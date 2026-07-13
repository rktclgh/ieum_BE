package shinhan.fibri.ieum.ai.question.retrieval;

import java.util.Objects;

public record GroundingSufficiencyResult(Decision decision, Reason reason) {

	public GroundingSufficiencyResult {
		decision = Objects.requireNonNull(decision, "decision must not be null");
		reason = Objects.requireNonNull(reason, "reason must not be null");
		if (decision != reason.decision()) {
			throw new IllegalArgumentException("reason must match decision");
		}
	}

	public enum Decision {
		SUFFICIENT,
		INSUFFICIENT
	}

	public enum Reason {
		NON_EMPTY_LOW_RISK_EVIDENCE(Decision.SUFFICIENT),
		HIGH_RISK_AUTHORITY_EVIDENCE_PRESENT(Decision.SUFFICIENT),
		EMPTY_EVIDENCE(Decision.INSUFFICIENT),
		HIGH_RISK_AUTHORITY_EVIDENCE_MISSING(Decision.INSUFFICIENT);

		private final Decision decision;

		Reason(Decision decision) {
			this.decision = decision;
		}

		Decision decision() {
			return decision;
		}
	}
}
