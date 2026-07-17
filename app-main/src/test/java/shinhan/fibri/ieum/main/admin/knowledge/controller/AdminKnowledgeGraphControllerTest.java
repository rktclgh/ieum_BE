package shinhan.fibri.ieum.main.admin.knowledge.controller;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;
import shinhan.fibri.ieum.common.knowledge.KnowledgeRelationPredicate;
import shinhan.fibri.ieum.main.admin.knowledge.dto.AdminKnowledgeGraphRequest;
import shinhan.fibri.ieum.main.admin.knowledge.service.KnowledgeGraphQueryService;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;

@WebMvcTest(AdminKnowledgeGraphController.class)
@AutoConfigureMockMvc(addFilters = false)
class AdminKnowledgeGraphControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private KnowledgeGraphQueryService service;

	@AfterEach
	void resetMocks() {
		reset(service);
	}

	@Test
	void rejectsLimitGreaterThanEightyWithValidationErrorShape() throws Exception {
		mockMvc.perform(get("/api/v1/admin/ai/knowledge/graph")
				.param("limit", "81"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code", is("VALIDATION_FAILED")))
			.andExpect(jsonPath("$.fieldErrors[*].field", hasItems("limit")));

		verifyNoInteractions(service);
	}

	@Test
	void bindsGraphFiltersOnTheCanonicalRoute() throws Exception {
		mockMvc.perform(get("/api/v1/admin/ai/knowledge/graph")
				.param("query", " visa ")
				.param("focus", " passport ")
				.param("predicate", "requires")
				.param("limit", "10"))
			.andExpect(status().isOk());

		verify(service).graph(new AdminKnowledgeGraphRequest(
			"visa",
			"passport",
			KnowledgeRelationPredicate.requires,
			10
		));
	}

	@TestConfiguration
	static class TestConfig {

		@Bean
		@Primary
		KnowledgeGraphQueryService knowledgeGraphQueryService() {
			return mock(KnowledgeGraphQueryService.class);
		}

		@Bean
		@Primary
		SessionTokenValidator sessionTokenValidator() {
			return mock(SessionTokenValidator.class);
		}
	}
}
