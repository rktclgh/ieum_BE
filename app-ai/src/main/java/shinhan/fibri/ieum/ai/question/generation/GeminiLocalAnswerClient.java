package shinhan.fibri.ieum.ai.question.generation;

@FunctionalInterface
interface GeminiLocalAnswerClient {

	GeminiLocalAnswerClientResponse generate(GeminiLocalAnswerRequest request);
}
