package shinhan.fibri.ieum.ai.question.webgrounding;

import com.google.genai.types.GenerateContentResponse;

interface GeminiWebGroundingClient {

	GenerateContentResponse generate(GeminiWebGroundingRequest request);
}
