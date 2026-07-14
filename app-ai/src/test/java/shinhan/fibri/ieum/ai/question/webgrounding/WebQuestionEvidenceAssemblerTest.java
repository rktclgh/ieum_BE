package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class WebQuestionEvidenceAssemblerTest {

	private static final Instant GENERATED_AT = Instant.parse("2026-07-13T03:04:05Z");
	private static final Instant RETRIEVED_AT = Instant.parse("2026-07-13T03:04:06Z");
	private static final String ANSWER = "서울 버스는 앞문으로 타고 뒷문으로 내립니다.";

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final WebQuestionEvidenceAssembler assembler = new WebQuestionEvidenceAssembler(
		objectMapper,
		Clock.fixed(RETRIEVED_AT, ZoneOffset.UTC)
	);

	@Test
	void assemblesCanonicalWebEvidenceInAnswerOrder() throws Exception {
		WebGroundedCitation later = citation(
			"하차 안내",
			"HTTPS://EXAMPLE.COM:443/guide/../guide/bus?lang=ko#door",
			"뒷문으로 내립니다",
			new BigDecimal("0.81"),
			15,
			24
		);
		WebGroundedCitation earlier = citation(
			"승차 안내",
			"https://Transit.Example.org:443/bus",
			"앞문으로 타고",
			new BigDecimal("0.92"),
			7,
			14
		);

		List<JsonNode> evidence = assembler.assemble(answer(List.of(later, earlier)));

		assertThat(evidence).hasSize(2);
		JsonNode first = evidence.getFirst();
		assertThat(fieldNames(first)).containsExactly(
			"type",
			"title",
			"excerpt",
			"url",
			"domain",
			"contentHash",
			"score",
			"startIndex",
			"endIndex",
			"retrievedAt"
		);
		assertThat(first.get("type").textValue()).isEqualTo("web");
		assertThat(first.get("url").textValue()).isEqualTo("https://transit.example.org/bus");
		assertThat(first.get("domain").textValue()).isEqualTo("transit.example.org");
		assertThat(first.get("retrievedAt").textValue()).isEqualTo(RETRIEVED_AT.toString());
		assertThat(first.get("contentHash").textValue()).isEqualTo(sha256(
			lengthPrefixed("https://transit.example.org/bus", "승차 안내", "앞문으로 타고")
		));

		JsonNode second = evidence.get(1);
		assertThat(second.get("url").textValue()).isEqualTo("https://example.com/guide/bus?lang=ko");
		assertThat(second.get("domain").textValue()).isEqualTo("example.com");
	}

	@Test
	void preservesEscapedPathAndQueryOctetsWhileCanonicalizingUrl() {
		JsonNode evidence = assembler.assemble(new WebGroundedAnswer(
			"근거",
			List.of(citation(
				"문서",
				"HTTPS://EXAMPLE.ORG:443/a%2Fb?q=x%2Fy#section",
				"근거",
				BigDecimal.ONE,
				0,
				2
			)),
			"google",
			"gemini-3.1-flash-lite",
			"web-grounding-v1",
			GENERATED_AT,
			1,
			1,
			"request-1",
			BigDecimal.ONE
		)).getFirst();

		assertThat(evidence.get("url").textValue())
			.isEqualTo("https://example.org/a%2Fb?q=x%2Fy");
	}

	@Test
	void returnsAnImmutableEvidenceList() {
		List<JsonNode> evidence = assembler.assemble(answer(List.of(citation(
			"승차 안내",
			"https://example.org/bus",
			"앞문으로 타고",
			BigDecimal.ONE,
			7,
			14
		))));

		assertThatThrownBy(() -> evidence.add(objectMapper.createObjectNode()))
			.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void citationRejectsInvalidSourceMetadataAndProbability() {
		assertThatThrownBy(() -> citation(" ", "https://example.org", "근거", BigDecimal.ONE, 0, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("title");
		assertThatThrownBy(() -> citation("제목", "ftp://example.org", "근거", BigDecimal.ONE, 0, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("HTTP(S)");
		assertThatThrownBy(() -> citation("제목", "https://user@example.org", "근거", BigDecimal.ONE, 0, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("userinfo");
		assertThatThrownBy(() -> citation("제목", "https:///missing-host", "근거", BigDecimal.ONE, 0, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("host");
		assertThatThrownBy(() -> citation("제목", "https://example.org", " ", BigDecimal.ONE, 0, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("excerpt");
		assertThatThrownBy(() -> citation("제목", "https://example.org", "근거", new BigDecimal("1.01"), 0, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("score");
		assertThatThrownBy(() -> citation("제목", "https://example.org", "근거", new BigDecimal("-0.01"), 0, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("score");
		assertThatThrownBy(() -> citation("제목", "https://example.org", "근거", BigDecimal.ONE, 1, 1))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("range");
	}

	@Test
	void answerRejectsCitationCountRangeAndUnicodeBoundaryViolations() {
		assertThatThrownBy(() -> answer(List.of()))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1 to 8");

		List<WebGroundedCitation> nine = new ArrayList<>();
		for (int index = 0; index < 9; index++) {
			nine.add(citation(
				"제목 " + index,
				"https://example.org/" + index,
				String.valueOf(index + 1),
				BigDecimal.ONE,
				index,
				index + 1
			));
		}
		assertThatThrownBy(() -> new WebGroundedAnswer(
			"123456789",
			nine,
			"google",
			"gemini-3.1-flash-lite",
			"web-grounding-v1",
			GENERATED_AT,
			1,
			1,
			"request-1",
			BigDecimal.ONE
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("1 to 8");

		assertThatThrownBy(() -> new WebGroundedAnswer(
			"A😀B",
			List.of(citation("제목", "https://example.org", "근거", BigDecimal.ONE, 2, 4)),
			"google",
			"gemini-3.1-flash-lite",
			"web-grounding-v1",
			GENERATED_AT,
			1,
			1,
			"request-1",
			BigDecimal.ONE
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("surrogate pair");

		assertThatThrownBy(() -> new WebGroundedAnswer(
			"짧음",
			List.of(citation("제목", "https://example.org", "근거", BigDecimal.ONE, 0, 3)),
			"google",
			"gemini-3.1-flash-lite",
			"web-grounding-v1",
			GENERATED_AT,
			1,
			1,
			"request-1",
			BigDecimal.ONE
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inside answer");
	}

	@Test
	void answerRejectsCitationExcerptThatDoesNotMatchItsRange() {
		assertThatThrownBy(() -> new WebGroundedAnswer(
			"정확한 근거",
			List.of(citation("제목", "https://example.org", "다른 값", BigDecimal.ONE, 0, 3)),
			"google",
			"gemini-3.1-flash-lite",
			"web-grounding-v1",
			GENERATED_AT,
			1,
			1,
			"request-1",
			BigDecimal.ONE
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("excerpt")
			.hasMessageContaining("answer range");
	}

	@Test
	void answerRejectsDuplicateRangesAndDefensivelyCopiesCitations() {
		List<WebGroundedCitation> citations = new ArrayList<>();
		citations.add(citation("제목", "https://one.example.org", "답", BigDecimal.ONE, 0, 1));
		WebGroundedAnswer copied = new WebGroundedAnswer(
			"답변",
			citations,
			"google",
			"gemini-3.1-flash-lite",
			"web-grounding-v1",
			GENERATED_AT,
			1,
			1,
			"request-1",
			BigDecimal.ONE
		);
		citations.clear();
		assertThat(copied.citations()).hasSize(1);

		assertThatThrownBy(() -> new WebGroundedAnswer(
			"답변",
			List.of(
				citation("첫째", "https://one.example.org", "답", BigDecimal.ONE, 0, 1),
				citation("둘째", "https://two.example.org", "답", BigDecimal.ONE, 0, 1)
			),
			"google",
			"gemini-3.1-flash-lite",
			"web-grounding-v1",
			GENERATED_AT,
			1,
			1,
			"request-1",
			BigDecimal.ONE
		)).isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("unique");
	}

	@Test
	void answerValidatesAndNormalizesProvenance() {
		WebGroundedAnswer answer = new WebGroundedAnswer(
			ANSWER,
			List.of(citation("제목", "https://example.org", "서", BigDecimal.ONE, 0, 1)),
			" google ",
			" gemini-3.1-flash-lite ",
			" web-grounding-v1 ",
			GENERATED_AT,
			0,
			12,
			" request-1 ",
			new BigDecimal("0.75")
		);

		assertThat(answer.provider()).isEqualTo("google");
		assertThat(answer.model()).isEqualTo("gemini-3.1-flash-lite");
		assertThat(answer.promptVersion()).isEqualTo("web-grounding-v1");
		assertThat(answer.requestId()).isEqualTo("request-1");

		assertThatThrownBy(() -> invalidProvenance(" ", "model", "prompt", GENERATED_AT, 1, 1, BigDecimal.ONE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("provider");
		assertThatThrownBy(() -> invalidProvenance("google", " ", "prompt", GENERATED_AT, 1, 1, BigDecimal.ONE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("model");
		assertThatThrownBy(() -> invalidProvenance("google", "model", " ", GENERATED_AT, 1, 1, BigDecimal.ONE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("promptVersion");
		assertThatThrownBy(() -> invalidProvenance("google", "model", "prompt", null, 1, 1, BigDecimal.ONE))
			.isInstanceOf(NullPointerException.class)
			.hasMessageContaining("generatedAt");
		assertThatThrownBy(() -> invalidProvenance("google", "model", "prompt", GENERATED_AT, -1, 1, BigDecimal.ONE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("inputTokens");
		assertThatThrownBy(() -> invalidProvenance("google", "model", "prompt", GENERATED_AT, 1, -1, BigDecimal.ONE))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("outputTokens");
		assertThatThrownBy(() -> invalidProvenance("google", "model", "prompt", GENERATED_AT, 1, 1, new BigDecimal("1.1")))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("groundingScore");
	}

	@Test
	void rejectsNullDependenciesAndInputs() {
		assertThatThrownBy(() -> new WebQuestionEvidenceAssembler(null, Clock.systemUTC()))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> new WebQuestionEvidenceAssembler(objectMapper, null))
			.isInstanceOf(NullPointerException.class);
		assertThatThrownBy(() -> assembler.assemble(null))
			.isInstanceOf(NullPointerException.class);
	}

	private WebGroundedAnswer answer(List<WebGroundedCitation> citations) {
		return new WebGroundedAnswer(
			ANSWER,
			citations,
			"google",
			"gemini-3.1-flash-lite",
			"web-grounding-v1",
			GENERATED_AT,
			20,
			30,
			"request-1",
			new BigDecimal("0.81")
		);
	}

	private WebGroundedAnswer invalidProvenance(
		String provider,
		String model,
		String promptVersion,
		Instant generatedAt,
		Integer inputTokens,
		Integer outputTokens,
		BigDecimal groundingScore
	) {
		return new WebGroundedAnswer(
			ANSWER,
			List.of(citation("제목", "https://example.org", "서", BigDecimal.ONE, 0, 1)),
			provider,
			model,
			promptVersion,
			generatedAt,
			inputTokens,
			outputTokens,
			"request-1",
			groundingScore
		);
	}

	private WebGroundedCitation citation(
		String title,
		String rawUrl,
		String excerpt,
		BigDecimal score,
		int startIndex,
		int endIndex
	) {
		return new WebGroundedCitation(title, URI.create(rawUrl), excerpt, score, startIndex, endIndex);
	}

	private List<String> fieldNames(JsonNode node) {
		List<String> fields = new ArrayList<>();
		node.fieldNames().forEachRemaining(fields::add);
		return fields;
	}

	private String sha256(String value) throws Exception {
		byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
		return java.util.HexFormat.of().formatHex(digest);
	}

	private String lengthPrefixed(String... values) {
		StringBuilder framed = new StringBuilder();
		for (String value : values) {
			framed.append(value.length()).append(':').append(value);
		}
		return framed.toString();
	}
}
