package shinhan.fibri.ieum.ai.knowledge.relations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import shinhan.fibri.ieum.ai.knowledge.accepted.AcceptedAnswerKnowledgeDocument;
import shinhan.fibri.ieum.ai.question.analysis.GeoScope;
import shinhan.fibri.ieum.ai.question.analysis.RegionContext;

class BedrockKnowledgeRelationCandidateExtractorTest {

	@Test
	void parsesJsonWrappedInMarkdownCodeFence() {
		BedrockKnowledgeRelationCandidateExtractor extractor = new BedrockKnowledgeRelationCandidateExtractor(
			new StaticChatModel("""
				```json
				{"candidates":[{"subject":"신청","predicate":"requires","object":"신분증","confidence":0.91,"evidence":"신분증을 지참합니다"}]}
				```
				"""),
			new ObjectMapper(),
			"apac.amazon.nova-lite-v1:0",
			1024
		);

		CandidateExtractionResult result = extractor.extract(document());

		assertThat(result.candidates()).singleElement().satisfies(candidate -> {
			assertThat(candidate.subject()).isEqualTo("신청");
			assertThat(candidate.predicate()).isEqualTo("requires");
			assertThat(candidate.object()).isEqualTo("신분증");
			assertThat(candidate.confidence()).isEqualTo(0.91);
			assertThat(candidate.evidence()).isEqualTo("신분증을 지참합니다");
		});
	}

	private AcceptedAnswerKnowledgeDocument document() {
		return new AcceptedAnswerKnowledgeDocument(
			"민원 안내",
			"a".repeat(64),
			"질문 제목: 민원 안내\n채택 답변: 신청 시 신분증을 지참합니다.",
			GeoScope.general,
			RegionContext.empty(),
			37.5665,
			126.9780
		);
	}

	private static final class StaticChatModel implements ChatModel {

		private final String response;

		private StaticChatModel(String response) {
			this.response = response;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			return new ChatResponse(List.of(new Generation(new AssistantMessage(response))));
		}
	}
}
