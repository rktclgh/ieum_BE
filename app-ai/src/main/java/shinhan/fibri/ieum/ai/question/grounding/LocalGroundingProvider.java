package shinhan.fibri.ieum.ai.question.grounding;

interface LocalGroundingProvider {

	String provider();

	String model();

	GroundingProviderResponse validate(GroundingModelPrompt prompt);

	GroundingProviderResponse repair(GroundingModelPrompt prompt);
}
