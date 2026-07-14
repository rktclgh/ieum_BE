package shinhan.fibri.ieum.ai.question.generation;

interface LocalAnswerProvider {

	String provider();

	String model();

	LocalAnswerProviderResponse generate(LocalAnswerPrompt prompt);
}
