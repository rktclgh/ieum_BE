package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.FinishReason;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.GenerateContentResponseUsageMetadata;
import com.google.genai.types.GroundingChunk;
import com.google.genai.types.GroundingChunkWeb;
import com.google.genai.types.GroundingMetadata;
import com.google.genai.types.GroundingSupport;
import com.google.genai.types.Part;
import com.google.genai.types.Segment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GeminiWebGroundingResponseParserTest {

	private static final Instant GENERATED_AT = Instant.parse("2026-07-14T01:02:03Z");
	private static final String MODEL = "gemini-3.1-flash-lite";

	private final GeminiWebGroundingResponseParser parser = new GeminiWebGroundingResponseParser(
		new PublicWebSourceUriValidator(),
		new MaterialClaimCoverageValidator()
	);

	@Test
	void parsesOnePartKoreanEmojiAnswerWithEverySupportedSentence() {
		String answer = "🙂 서울 버스는 앞문으로 탑니다. 수원 버스도 앞문으로 탑니다.";
		int secondStart = answer.indexOf("수원");
		List<GroundingChunk> chunks = List.of(
			webChunk("서울 버스 안내", "https://seoul.example.com/bus"),
			webChunk("수원 버스 안내", "https://suwon.example.com/bus")
		);
		List<GroundingSupport> supports = List.of(
			support(answer, 0, secondStart, List.of(0), List.of(0.91f), null),
			support(answer, secondStart, answer.length(), List.of(1), List.of(0.82f), 0)
		);
		GenerateContentResponse response = response(answer, chunks, supports).toBuilder()
			.modelVersion(" gemini-search-grounded-001 ")
			.responseId(" request-123 ")
			.usageMetadata(GenerateContentResponseUsageMetadata.builder()
				.promptTokenCount(21)
				.candidatesTokenCount(13)
				.build())
			.build();

		Optional<WebGroundedAnswer> parsed = parser.parse(response, properties(), GENERATED_AT);

		assertThat(parsed).isPresent();
		WebGroundedAnswer grounded = parsed.orElseThrow();
		assertThat(grounded.answer()).isEqualTo(answer);
		assertThat(grounded.citations()).hasSize(2);
		assertThat(grounded.citations().get(0)).satisfies(citation -> {
			assertThat(citation.title()).isEqualTo("서울 버스 안내");
			assertThat(citation.excerpt()).isEqualTo(answer.substring(0, secondStart));
			assertThat(citation.startIndex()).isZero();
			assertThat(citation.endIndex()).isEqualTo(secondStart);
			assertThat(citation.score()).isEqualByComparingTo("0.91");
		});
		assertThat(grounded.citations().get(1)).satisfies(citation -> {
			assertThat(citation.title()).isEqualTo("수원 버스 안내");
			assertThat(citation.excerpt()).isEqualTo(answer.substring(secondStart));
			assertThat(citation.score()).isEqualByComparingTo("0.82");
		});
		assertThat(grounded.provider()).isEqualTo("gemini");
		assertThat(grounded.model()).isEqualTo("gemini-search-grounded-001");
		assertThat(grounded.promptVersion()).isEqualTo("question-web-grounding-v1");
		assertThat(grounded.generatedAt()).isEqualTo(GENERATED_AT);
		assertThat(grounded.inputTokens()).isEqualTo(21);
		assertThat(grounded.outputTokens()).isEqualTo(13);
		assertThat(grounded.requestId()).isEqualTo("request-123");
		assertThat(grounded.groundingScore()).isEqualByComparingTo("0.82");
	}

	@Test
	void acceptsAbsentPartIndexCandidateIndexAndFinishReasonAsTheSinglePartDefaults() {
		String answer = "서울 버스는 앞문으로 탑니다.";

		Optional<WebGroundedAnswer> parsed = parser.parse(
			response(answer, List.of(webChunk()), List.of(
				support(answer, 0, answer.length(), List.of(0), null, null)
			)),
			properties(),
			GENERATED_AT
		);

		assertThat(parsed).isPresent();
		assertThat(parsed.orElseThrow().citations()).singleElement().satisfies(citation ->
			assertThat(citation.score()).isEqualByComparingTo(BigDecimal.ZERO)
		);
	}

	@Test
	void rejectsANonzeroPartIndex() {
		String answer = "서울 버스입니다.";

		assertThat(parser.parse(
			response(answer, List.of(webChunk()), List.of(
				support(answer, 0, answer.length(), List.of(0), null, 1)
			)),
			properties(),
			GENERATED_AT
		)).isEmpty();
	}

	@Test
	void selectsTheHighestConfidenceValidWebChunkAndKeepsProviderOrderOnTies() {
		String answer = "서울 버스는 앞문으로 탑니다.";
		List<GroundingChunk> chunks = List.of(
			webChunk("낮은 점수", "https://low.example.com/source"),
			webChunk("높은 점수 첫 출처", "https://first.example.com/source"),
			webChunk("높은 점수 두 번째 출처", "https://second.example.com/source")
		);

		WebGroundedAnswer parsed = parser.parse(
			response(answer, chunks, List.of(support(
				answer,
				0,
				answer.length(),
				List.of(0, 1, 2),
				List.of(0.2f, 0.9f, 0.9f),
				0
			))),
			properties(),
			GENERATED_AT
		).orElseThrow();

		assertThat(parsed.citations()).singleElement().satisfies(citation -> {
			assertThat(citation.title()).isEqualTo("높은 점수 첫 출처");
			assertThat(citation.score()).isEqualByComparingTo("0.9");
		});
	}

	@Test
	void absentConfidenceSelectsTheFirstValidReferencedWebChunkWithUnknownZeroScore() {
		String answer = "서울 버스는 앞문으로 탑니다.";
		List<GroundingChunk> chunks = List.of(
			GroundingChunk.builder().build(),
			webChunk("사설 주소", "http://127.0.0.1/private"),
			webChunk("공개 첫 출처", "https://first.example.com/source"),
			webChunk("공개 두 번째 출처", "https://second.example.com/source")
		);

		WebGroundedAnswer parsed = parser.parse(
			response(answer, chunks, List.of(support(
				answer,
				0,
				answer.length(),
				List.of(0, 1, 2, 3),
				null,
				0
			))),
			properties(),
			GENERATED_AT
		).orElseThrow();

		assertThat(parsed.citations()).singleElement().satisfies(citation -> {
			assertThat(citation.title()).isEqualTo("공개 첫 출처");
			assertThat(citation.score()).isEqualByComparingTo(BigDecimal.ZERO);
		});
		assertThat(parsed.groundingScore()).isEqualByComparingTo(BigDecimal.ZERO);
	}

	@Test
	void rejectsMalformedConfidenceCollectionsAndValues() {
		String answer = "서울 버스입니다.";
		List<List<Float>> malformedScores = List.of(
			List.of(),
			List.of(0.4f),
			List.of(Float.NaN, 0.5f),
			List.of(Float.POSITIVE_INFINITY, 0.5f),
			List.of(-0.1f, 0.5f),
			List.of(1.1f, 0.5f)
		);

		assertThat(malformedScores).allSatisfy(scores -> assertThat(parser.parse(
			response(answer, List.of(webChunk(), webChunk()), List.of(support(
				answer,
				0,
				answer.length(),
				List.of(0, 1),
				scores,
				0
			))),
			properties(),
			GENERATED_AT
		)).isEmpty());
	}

	@Test
	void rejectsMissingRequiredProviderStructures() {
		String answer = "서울 버스입니다.";
		GroundingMetadata validMetadata = metadata(
			List.of(webChunk()),
			List.of(support(answer, 0, answer.length(), List.of(0), null, 0))
		);
		GroundingSupport validSupport = support(
			answer,
			0,
			answer.length(),
			List.of(0),
			null,
			0
		);
		List<GenerateContentResponse> malformed = List.of(
			GenerateContentResponse.builder().build(),
			GenerateContentResponse.builder().candidates(List.of()).build(),
			response(Candidate.builder().build()),
			response(Candidate.builder().content(Content.builder().build()).build()),
			response(candidate(answer, null)),
			response(candidate(answer, GroundingMetadata.builder()
				.groundingSupports(List.of(validSupport))
				.build())),
			response(candidate(answer, GroundingMetadata.builder()
				.groundingChunks(List.of())
				.groundingSupports(List.of(validSupport))
				.build())),
			response(candidate(answer, GroundingMetadata.builder()
				.groundingChunks(List.of(webChunk()))
				.build())),
			response(candidate(answer, GroundingMetadata.builder()
				.groundingChunks(List.of(webChunk()))
				.groundingSupports(List.of())
				.build())),
			response(candidate(answer, metadata(
				List.of(webChunk()),
				List.of(GroundingSupport.builder().build())
			))),
			response(candidate(answer, metadata(
				List.of(webChunk()),
				List.of(GroundingSupport.builder()
					.segment(Segment.builder().endIndex(bytes(answer)).text(answer).build())
					.groundingChunkIndices(0)
					.build())
			))),
			response(candidate(answer, metadata(
				List.of(webChunk()),
				List.of(GroundingSupport.builder()
					.segment(Segment.builder().startIndex(0).text(answer).build())
					.groundingChunkIndices(0)
					.build())
			))),
			response(candidate(answer, metadata(
				List.of(webChunk()),
				List.of(GroundingSupport.builder()
					.segment(Segment.builder().startIndex(0).endIndex(bytes(answer)).build())
					.groundingChunkIndices(0)
					.build())
			))),
			response(candidate(answer, metadata(
				List.of(webChunk()),
				List.of(GroundingSupport.builder()
					.segment(Segment.builder()
						.startIndex(0)
						.endIndex(bytes(answer))
						.text(answer)
						.build())
					.build())
			))),
			response(candidate(answer, metadata(
				List.of(webChunk()),
				List.of(GroundingSupport.builder()
					.segment(Segment.builder()
						.startIndex(0)
						.endIndex(bytes(answer))
						.text(answer)
						.build())
					.groundingChunkIndices(List.of())
					.build())
			)))
		);

		assertThat(parser.parse(null, properties(), GENERATED_AT)).isEmpty();
		assertThat(malformed).allSatisfy(response -> assertThat(
			parser.parse(response, properties(), GENERATED_AT)
		).isEmpty());
		assertThat(validMetadata).isNotNull();
	}

	@Test
	void rejectsMissingOrBlankAnswerTextAndMultipleOrThoughtParts() {
		String answer = "서울 버스입니다.";
		GroundingMetadata metadata = metadata(
			List.of(webChunk()),
			List.of(support(answer, 0, answer.length(), List.of(0), null, 0))
		);
		List<GenerateContentResponse> malformed = List.of(
			response(candidate(List.of(), metadata)),
			response(candidate(List.of(Part.builder().build()), metadata)),
			response(candidate(List.of(Part.builder().text("  ").build()), metadata)),
			response(candidate(List.of(
				Part.builder().text(answer).build(),
				Part.builder().text("두 번째 답변").build()
			), metadata)),
			response(candidate(List.of(Part.builder().text(answer).thought(true).build()), metadata))
		);

		assertThat(malformed).allSatisfy(response -> assertThat(
			parser.parse(response, properties(), GENERATED_AT)
		).isEmpty());
	}

	@Test
	void rejectsMultipleCandidatesNonzeroCandidateIndexAndNonStopFinishReasons() {
		String answer = "서울 버스입니다.";
		Candidate valid = validCandidate(answer);
		List<GenerateContentResponse> malformed = new ArrayList<>();
		malformed.add(GenerateContentResponse.builder().candidates(valid, valid).build());
		malformed.add(response(valid.toBuilder().index(1).build()));
		for (FinishReason.Known reason : FinishReason.Known.values()) {
			if (reason != FinishReason.Known.STOP) {
				malformed.add(response(valid.toBuilder().finishReason(reason).build()));
			}
		}
		malformed.add(response(valid.toBuilder().finishReason("provider-new-reason").build()));

		assertThat(malformed).allSatisfy(response -> assertThat(
			parser.parse(response, properties(), GENERATED_AT)
		).isEmpty());

		assertThat(parser.parse(
			response(valid.toBuilder().index(0).finishReason(FinishReason.Known.STOP).build()),
			properties(),
			GENERATED_AT
		)).isPresent();
	}

	@Test
	void rejectsEveryOutOfBoundsChunkIndexEvenWhenAnotherReferenceIsValid() {
		String answer = "서울 버스입니다.";

		assertThat(parser.parse(
			response(answer, List.of(webChunk()), List.of(support(
				answer,
				0,
				answer.length(),
				List.of(0, 1),
				null,
				0
			))),
			properties(),
			GENERATED_AT
		)).isEmpty();
	}

	@Test
	void rejectsWhenNoReferencedChunkHasAValidPublicWebSource() {
		String answer = "공식 근거는 https://example.com 입니다.";

		assertThat(parser.parse(
			response(answer, List.of(
				GroundingChunk.builder().build(),
				webChunk(" ", "https://example.com/source"),
				webChunk("내부", "http://10.0.0.1/private")
			), List.of(support(
				answer,
				0,
				answer.length(),
				List.of(0, 1, 2),
				null,
				0
			))),
			properties(),
			GENERATED_AT
		)).isEmpty();
	}

	@Test
	void deduplicatesEqualRangesByHigherScoreAndKeepsTheEarlierSourceOnTies() {
		String answer = "서울 버스입니다.";
		List<GroundingSupport> supports = List.of(
			support(answer, 0, answer.length(), List.of(0), List.of(0.2f), 0),
			support(answer, 0, answer.length(), List.of(1), List.of(0.8f), 0),
			support(answer, 0, answer.length(), List.of(2), List.of(0.8f), 0)
		);

		WebGroundedAnswer parsed = parser.parse(
			response(answer, List.of(
				webChunk("낮은 출처", "https://low.example.com/source"),
				webChunk("높은 첫 출처", "https://high-first.example.com/source"),
				webChunk("높은 둘째 출처", "https://high-second.example.com/source")
			), supports),
			properties(),
			GENERATED_AT
		).orElseThrow();

		assertThat(parsed.citations()).singleElement().satisfies(citation -> {
			assertThat(citation.title()).isEqualTo("높은 첫 출처");
			assertThat(citation.score()).isEqualByComparingTo("0.8");
		});
	}

	@Test
	void rejectsNineDistinctCitationRangesInsteadOfTruncatingThem() {
		String answer = "가.나.다.라.마.바.사.아.자.";
		List<GroundingSupport> supports = new ArrayList<>();
		for (int index = 0; index < answer.length(); index += 2) {
			supports.add(support(answer, index, index + 1, List.of(0), null, 0));
		}

		assertThat(parser.parse(
			response(answer, List.of(webChunk()), supports),
			properties(),
			GENERATED_AT
		)).isEmpty();
	}

	@Test
	void rejectsAnAnswerWithASecondUnsupportedMaterialSentence() {
		String answer = "첫 문장. 둘째 문장.";
		int firstEnd = answer.indexOf('.') + 1;

		assertThat(parser.parse(
			response(answer, List.of(webChunk()), List.of(
				support(answer, 0, firstEnd, List.of(0), null, 0)
			)),
			properties(),
			GENERATED_AT
		)).isEmpty();
	}

	@Test
	void normalizesProvenanceAndDropsUntrustedOutOfBoundsMetadata() {
		String answer = "서울 버스입니다.";
		GenerateContentResponse response = response(answer, List.of(webChunk()), List.of(
			support(answer, 0, answer.length(), List.of(0), List.of(0.7f), 0)
		)).toBuilder()
			.modelVersion("x".repeat(121))
			.responseId("r".repeat(201))
			.usageMetadata(GenerateContentResponseUsageMetadata.builder()
				.promptTokenCount(-1)
				.candidatesTokenCount(-2)
				.build())
			.build();

		WebGroundedAnswer parsed = parser.parse(response, properties(), GENERATED_AT).orElseThrow();

		assertThat(parsed.model()).isEqualTo(MODEL);
		assertThat(parsed.requestId()).isNull();
		assertThat(parsed.inputTokens()).isNull();
		assertThat(parsed.outputTokens()).isNull();
	}

	@Test
	void absorbsUncheckedFailuresFromMalformedProviderObjects() {
		GenerateContentResponse malformed = mock(GenerateContentResponse.class);
		when(malformed.candidates()).thenThrow(new IllegalStateException("raw provider body"));

		assertThat(parser.parse(malformed, properties(), GENERATED_AT)).isEmpty();
		assertThat(parser.parse(validResponse("서울 버스입니다."), null, GENERATED_AT)).isEmpty();
		assertThat(parser.parse(validResponse("서울 버스입니다."), properties(), null)).isEmpty();
	}

	private GenerateContentResponse validResponse(String answer) {
		return response(answer, List.of(webChunk()), List.of(
			support(answer, 0, answer.length(), List.of(0), null, 0)
		));
	}

	private Candidate validCandidate(String answer) {
		return candidate(answer, metadata(
			List.of(webChunk()),
			List.of(support(answer, 0, answer.length(), List.of(0), null, 0))
		));
	}

	private GenerateContentResponse response(
		String answer,
		List<GroundingChunk> chunks,
		List<GroundingSupport> supports
	) {
		return response(candidate(answer, metadata(chunks, supports)));
	}

	private GenerateContentResponse response(Candidate candidate) {
		return GenerateContentResponse.builder().candidates(candidate).build();
	}

	private Candidate candidate(String answer, GroundingMetadata metadata) {
		return candidate(List.of(Part.builder().text(answer).build()), metadata);
	}

	private Candidate candidate(List<Part> parts, GroundingMetadata metadata) {
		Candidate.Builder builder = Candidate.builder()
			.content(Content.builder().parts(parts).build());
		if (metadata != null) {
			builder.groundingMetadata(metadata);
		}
		return builder.build();
	}

	private GroundingMetadata metadata(
		List<GroundingChunk> chunks,
		List<GroundingSupport> supports
	) {
		return GroundingMetadata.builder()
			.groundingChunks(chunks)
			.groundingSupports(supports)
			.build();
	}

	private GroundingSupport support(
		String answer,
		int startIndex,
		int endIndex,
		List<Integer> chunkIndices,
		List<Float> confidenceScores,
		Integer partIndex
	) {
		Segment.Builder segment = Segment.builder()
			.startIndex(bytes(answer.substring(0, startIndex)))
			.endIndex(bytes(answer.substring(0, endIndex)))
			.text(answer.substring(startIndex, endIndex));
		if (partIndex != null) {
			segment.partIndex(partIndex);
		}
		GroundingSupport.Builder support = GroundingSupport.builder()
			.segment(segment.build())
			.groundingChunkIndices(chunkIndices);
		if (confidenceScores != null) {
			support.confidenceScores(confidenceScores);
		}
		return support.build();
	}

	private GroundingChunk webChunk() {
		return webChunk("공식 출처", "https://example.com/source");
	}

	private GroundingChunk webChunk(String title, String uri) {
		return GroundingChunk.builder()
			.web(GroundingChunkWeb.builder().title(title).uri(uri).build())
			.build();
	}

	private int bytes(String value) {
		return value.getBytes(StandardCharsets.UTF_8).length;
	}

	private WebGroundingProperties properties() {
		return new WebGroundingProperties(
			MODEL,
			"test-only-api-key",
			"question-web-grounding-v1",
			1024,
			Duration.ofSeconds(45)
		);
	}
}
