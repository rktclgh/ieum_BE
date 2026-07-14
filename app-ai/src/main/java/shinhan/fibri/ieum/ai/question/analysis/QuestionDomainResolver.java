package shinhan.fibri.ieum.ai.question.analysis;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class QuestionDomainResolver {

	private static final Map<String, QuestionDomain> DOMAINS_BY_ALIAS = aliases();

	private QuestionDomainResolver() {
	}

	public static QuestionDomain resolve(String modelDomain) {
		String normalized = normalize(modelDomain);
		if (normalized == null) {
			return QuestionDomain.unknown;
		}
		return DOMAINS_BY_ALIAS.getOrDefault(normalized, QuestionDomain.unknown);
	}

	private static Map<String, QuestionDomain> aliases() {
		Map<String, QuestionDomain> aliases = new HashMap<>();
		Arrays.stream(QuestionDomain.values())
			.forEach(domain -> aliases.put(domain.name(), domain));
		alias(aliases, QuestionDomain.legal, "law", "legal_advice");
		alias(aliases, QuestionDomain.labor,
			"employment", "employment_law", "work_labor", "work_and_labor", "work_and_business");
		alias(aliases, QuestionDomain.insurance,
			"health_insurance", "healthcare_insurance", "national_health_insurance", "social_insurance");
		alias(aliases, QuestionDomain.medical, "health", "health_care", "healthcare");
		alias(aliases, QuestionDomain.immigration,
			"immigration_and_identity", "visa", "visa_immigration", "visa_and_immigration");
		alias(aliases, QuestionDomain.tax, "taxation");
		alias(aliases, QuestionDomain.pension, "national_pension", "retirement_pension");
		alias(aliases, QuestionDomain.finance, "banking", "finance_tax_and_insurance");
		alias(aliases, QuestionDomain.emergency,
			"emergency_services", "safety", "safety_law_and_emergencies");
		alias(aliases, QuestionDomain.digital, "digital_and_communications");
		alias(aliases, QuestionDomain.housing, "housing_and_utilities");
		alias(aliases, QuestionDomain.family, "family_and_education");
		alias(aliases, QuestionDomain.food, "food_and_daily_shopping");
		alias(aliases, QuestionDomain.transport,
			"mobility", "mobility_and_travel", "public_transport", "public_transportation");
		alias(aliases, QuestionDomain.public_services, "public_services_and_community");
		alias(aliases, QuestionDomain.culture, "culture_and_communication");
		alias(aliases, QuestionDomain.environment, "environment_and_seasonality");
		alias(aliases, QuestionDomain.household, "household_specific_life");
		return Map.copyOf(aliases);
	}

	private static void alias(
		Map<String, QuestionDomain> aliases,
		QuestionDomain domain,
		String... values
	) {
		for (String value : values) {
			aliases.put(value, domain);
		}
	}

	private static String normalize(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String normalized = value.trim()
			.toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", "_")
			.replaceAll("^_+|_+$", "");
		return normalized.isEmpty() ? null : normalized;
	}
}
