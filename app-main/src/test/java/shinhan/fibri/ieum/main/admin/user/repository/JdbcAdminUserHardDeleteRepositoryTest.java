package shinhan.fibri.ieum.main.admin.user.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class JdbcAdminUserHardDeleteRepositoryTest {

	private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
	private final JdbcAdminUserHardDeleteRepository repository = new JdbcAdminUserHardDeleteRepository(jdbc);

	@Test
	@SuppressWarnings({"unchecked", "rawtypes"})
	void hardDeleteDeletesCollectedFileRowsInBatches() {
		List<UUID> fileIds = IntStream.range(0, 1001)
			.mapToObj(index -> new UUID(0L, index + 1L))
			.toList();
		when(jdbc.query(anyString(), any(SqlParameterSource.class), any(RowMapper.class)))
			.thenAnswer(invocation -> {
				RowMapper mapper = invocation.getArgument(2);
				List<Object> rows = new ArrayList<>();
				for (int index = 0; index < fileIds.size(); index++) {
					ResultSet resultSet = mock(ResultSet.class);
					when(resultSet.getObject("file_id")).thenReturn(fileIds.get(index));
					when(resultSet.getString("s3_key")).thenReturn("final/10/file/" + index + "/original.jpg");
					rows.add(mapper.mapRow(resultSet, index));
				}
				return rows;
			});
		when(jdbc.update(anyString(), any(SqlParameterSource.class))).thenReturn(0);

		repository.hardDelete(10L);

		ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
		ArgumentCaptor<SqlParameterSource> paramsCaptor = ArgumentCaptor.forClass(SqlParameterSource.class);
		org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.times(4)).update(sqlCaptor.capture(), paramsCaptor.capture());
		List<Integer> fileDeleteBatchSizes = IntStream.range(0, sqlCaptor.getAllValues().size())
			.filter(index -> sqlCaptor.getAllValues().get(index).startsWith("DELETE FROM files"))
			.mapToObj(index -> (MapSqlParameterSource) paramsCaptor.getAllValues().get(index))
			.map(params -> ((List<?>) params.getValue("fileIds")).size())
			.toList();

		assertThat(fileDeleteBatchSizes).containsExactly(1000, 1);
	}
}
