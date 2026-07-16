package shinhan.fibri.ieum.main.admin.report.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.report.domain.ReportTargetType;
import tools.jackson.databind.ObjectMapper;

class AdminReportJsonSanitizerTest {

	private final AdminReportJsonSanitizer sanitizer = new AdminReportJsonSanitizer(new ObjectMapper());

	@Test
	void keepsOnlySafeMessageSnapshotFieldsWithoutDoubleEncodingJson() {
		var result = sanitizer.sanitizeContextSnapshot(
			ReportTargetType.message,
			"""
				{
				  "schemaVersion": 1,
				  "roomId": 10,
				  "privateRoot": "remove",
				  "before": [{"messageId": 1, "senderId": 2, "content": "before", "secret": "remove"}],
				  "reported": {"messageId": 3, "senderId": 4, "content": "reported", "imageFileId": null, "createdAt": "2026-07-14T10:00:00Z", "secret": "remove"},
				  "after": []
				}
				"""
		);

		assertThat(result.isObject()).isTrue();
		assertThat(result.path("roomId").asLong()).isEqualTo(10L);
		assertThat(result.at("/reported/messageId").asLong()).isEqualTo(3L);
		assertThat(result.has("privateRoot")).isFalse();
		assertThat(result.at("/reported/secret").isMissingNode()).isTrue();
	}

	@Test
	void keepsOnlySafeAnswerSnapshotFields() {
		var result = sanitizer.sanitizeContextSnapshot(
			ReportTargetType.answer,
			"""
				{"schemaVersion":1,"targetType":"answer","questionId":10,
				 "reported":{"answerId":20,"authorId":null,"isAi":true,"content":"answer",
				 "imageFileIds":["11111111-1111-1111-1111-111111111111"],"createdAt":"2026-07-14T10:00:00Z",
				 "providerPayload":{"token":"remove"}}}
				"""
		);

		assertThat(result.at("/reported/answerId").asLong()).isEqualTo(20L);
		assertThat(result.at("/reported/authorId").isNull()).isTrue();
		assertThat(result.at("/reported/imageFileIds").isArray()).isTrue();
		assertThat(result.at("/reported/providerPayload").isMissingNode()).isTrue();
	}

	@Test
	void keepsOnlySafeScheduleSnapshotFields() {
		var result = sanitizer.sanitizeContextSnapshot(
			ReportTargetType.schedule,
			"""
				{"schemaVersion":1,"targetType":"schedule",
				 "reported":{"scheduleId":31,"meetingId":3,"createdByUserId":77,
				 "title":"용산 와인바에서 봅시다","locationName":"용산역 1번 출구",
				 "startsAt":"2099-07-20T19:00:00+09:00","endsAt":null,"status":"scheduled",
				 "privateNote":"remove"},"providerPayload":{"token":"remove"}}
				"""
		);

		assertThat(result.path("targetType").asString()).isEqualTo("schedule");
		assertThat(result.at("/reported/scheduleId").asLong()).isEqualTo(31L);
		assertThat(result.at("/reported/locationName").asString()).isEqualTo("용산역 1번 출구");
		assertThat(result.at("/reported/endsAt").isNull()).isTrue();
		assertThat(result.at("/reported/privateNote").isMissingNode()).isTrue();
		assertThat(result.path("providerPayload").isMissingNode()).isTrue();
	}

	@Test
	void allowlistsAiResultAndDropsProviderPayloadAndChainOfThought() {
		var result = sanitizer.sanitizeAiResult("""
			{"category":"abuse","severity":"high","evidence":["message"],"matchedRules":["R-1"],
			 "policySnapshot":{"version":"v1"},"modelVersion":"model","promptVersion":"prompt",
			 "fallbackUsed":false,"providerAttempts":[{"raw":"secret"}],"chainOfThought":"secret","raw":"secret"}
			""");

		assertThat(result.propertyNames()).containsExactlyInAnyOrder(
			"category", "severity", "evidence", "matchedRules", "policySnapshot",
			"modelVersion", "promptVersion", "fallbackUsed"
		);
		assertThat(result.toString()).doesNotContain("providerAttempts", "chainOfThought", "secret");
	}

	@Test
	void nullOrMalformedJsonProducesNoSnapshotInsteadOfLeakingRawText() {
		assertThat(sanitizer.sanitizeContextSnapshot(ReportTargetType.message, null)).isNull();
		assertThat(sanitizer.sanitizeContextSnapshot(ReportTargetType.message, "not-json")).isNull();
		assertThat(sanitizer.sanitizeAiResult(null)).isNull();
		assertThat(sanitizer.sanitizeAiResult("not-json")).isNull();
	}
}
