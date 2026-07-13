package shinhan.fibri.ieum.common.ai.question.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class InternalQuestionRecommendationDtoTest {

	@Test
	void keepsTheSharedQuestionRecommendationWireContract() throws Exception {
		QuestionRecommendationLocation location = new QuestionRecommendationLocation(
			new BigDecimal("37.566500"),
			new BigDecimal("126.978000"),
			"Seoul",
			"City Hall",
			"current"
		);
		InternalQuestionRecommendationRequest request = new InternalQuestionRecommendationRequest(
			"How do I open an account?",
			"I need help opening a bank account.",
			location,
			3
		);
		RecommendedAcceptedAnswer acceptedAnswer = new RecommendedAcceptedAnswer("Use your passport.", true);
		InternalQuestionRecommendationItem item = new InternalQuestionRecommendationItem(
			101L,
			202L,
			"Opening a bank account",
			new BigDecimal("0.9876"),
			"NEARBY",
			false,
			acceptedAnswer
		);
		InternalQuestionRecommendationResponse response = new InternalQuestionRecommendationResponse(List.of(item));

		assertThat(request.location()).isEqualTo(location);
		assertThat(item.relevanceScore()).isEqualByComparingTo("0.9876");
		assertThat(response.items()).containsExactly(item);

		ObjectMapper objectMapper = new ObjectMapper();
		JsonNode requestJson = objectMapper.readTree(objectMapper.writeValueAsString(request));
		assertThat(requestJson.path("title").asText()).isEqualTo("How do I open an account?");
		assertThat(requestJson.path("content").asText()).isEqualTo("I need help opening a bank account.");
		assertThat(requestJson.path("candidateLimit").asInt()).isEqualTo(3);
		assertThat(requestJson.path("location").path("lat").decimalValue()).isEqualByComparingTo("37.566500");
		assertThat(requestJson.path("location").path("lng").decimalValue()).isEqualByComparingTo("126.978000");
		assertThat(requestJson.path("location").path("address").asText()).isEqualTo("Seoul");
		assertThat(requestJson.path("location").path("detailAddress").asText()).isEqualTo("City Hall");
		assertThat(requestJson.path("location").path("label").asText()).isEqualTo("current");

		JsonNode responseJson = objectMapper.readTree(objectMapper.writeValueAsString(response));
		JsonNode itemJson = responseJson.path("items").get(0);
		assertThat(itemJson.path("questionId").asLong()).isEqualTo(101L);
		assertThat(itemJson.path("authorId").asLong()).isEqualTo(202L);
		assertThat(itemJson.path("title").asText()).isEqualTo("Opening a bank account");
		assertThat(itemJson.path("relevanceScore").decimalValue()).isEqualByComparingTo("0.9876");
		assertThat(itemJson.path("geoScope").asText()).isEqualTo("NEARBY");
		assertThat(itemJson.path("isResolved").asBoolean()).isFalse();
		assertThat(itemJson.path("acceptedAnswer").path("content").asText()).isEqualTo("Use your passport.");
		assertThat(itemJson.path("acceptedAnswer").path("isAi").asBoolean()).isTrue();

		InternalQuestionRecommendationResponse responseWithNullAcceptedAnswer =
			new InternalQuestionRecommendationResponse(List.of(new InternalQuestionRecommendationItem(
				102L,
				203L,
				"Unanswered question",
				new BigDecimal("0.5000"),
				"GLOBAL",
				false,
				null
			)));
		JsonNode nullableAnswerJson = objectMapper.readTree(objectMapper.writeValueAsString(responseWithNullAcceptedAnswer));
		assertThat(nullableAnswerJson.path("items").get(0).has("acceptedAnswer")).isTrue();
		assertThat(nullableAnswerJson.path("items").get(0).path("acceptedAnswer").isNull()).isTrue();
	}
}
