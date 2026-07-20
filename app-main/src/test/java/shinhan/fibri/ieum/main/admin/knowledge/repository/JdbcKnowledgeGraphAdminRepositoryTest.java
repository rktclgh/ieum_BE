package shinhan.fibri.ieum.main.admin.knowledge.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcKnowledgeGraphAdminRepositoryTest {

	@Test
	@SuppressWarnings({ "rawtypes", "unchecked" })
	void includesCuratedSourcesWhileKeepingAcceptedAnswerEligibilityGuard() {
		JdbcClient jdbc = mock(JdbcClient.class);
		JdbcClient.StatementSpec statement = mock(JdbcClient.StatementSpec.class);
		JdbcClient.MappedQuerySpec<JdbcKnowledgeGraphAdminRepository.GraphRelationRow> query = mock(
			JdbcClient.MappedQuerySpec.class
		);
		when(jdbc.sql(anyString())).thenReturn(statement);
		when(statement.param(anyString(), any())).thenReturn(statement);
		when(statement.query(any(RowMapper.class))).thenReturn((JdbcClient.MappedQuerySpec) query);
		when(query.list()).thenReturn(List.of());

		new JdbcKnowledgeGraphAdminRepository(jdbc).findGraphRelations(null, null, null, 81);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		verify(jdbc).sql(sqlCaptor.capture());
		String sql = sqlCaptor.getValue();

		assertThat(sql)
			.contains("LEFT JOIN questions q ON q.question_id = ks.question_id")
			.contains("ks.source_type <> 'accepted_human_answer'")
			.doesNotContain("WHERE ks.source_type = 'accepted_human_answer'");
	}
}
