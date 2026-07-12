package shinhan.fibri.ieum.common.ai.report.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportReviewContractTest {

	@Test
	void keepsTheSharedReviewRequestWireContract() throws Exception {
		UUID attemptId = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
		ReportReviewImage image = new ReportReviewImage("image/webp", "https://files.example.test/display.webp");
		ReportReviewMessage message = new ReportReviewMessage(
			10L, "reported_user", "message content", image, "2026-07-11T10:01:00Z"
		);

		ReportReviewRequest request = new ReportReviewRequest(
			900L, attemptId, 10L, "harassment", "detail", "a".repeat(64), List.of(message)
		);

		assertThat(request.reviewAttemptId()).isEqualTo(attemptId);
		assertThat(request.messages()).containsExactly(message);
		assertThat(request.messages().getFirst().image()).isEqualTo(image);

		JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(request));
		assertThat(json.path("reportId").asLong()).isEqualTo(900L);
		assertThat(json.path("reviewAttemptId").asText()).isEqualTo(attemptId.toString());
		assertThat(json.path("contextHash").asText()).isEqualTo("a".repeat(64));
		assertThat(json.path("messages").get(0).path("actor").asText()).isEqualTo("reported_user");
		assertThat(json.path("messages").get(0).path("image").path("contentType").asText()).isEqualTo("image/webp");
	}
}
