package shinhan.fibri.ieum.main.pin.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;

class JdbcPinWriterTest {

	@Test
	void storesLocationSnapshotWithLongitudeBeforeLatitude() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), org.mockito.ArgumentMatchers.any(Object[].class)))
			.thenReturn(10L);
		JdbcPinWriter writer = new JdbcPinWriter(jdbcTemplate);

		Long pinId = writer.create(42L, PinType.question, new LocationSnapshot(
			37.5666103, 126.9783882, "서울특별시 중구 세종대로 110", null, null
		));

		ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
		verify(jdbcTemplate).queryForObject(anyString(), org.mockito.ArgumentMatchers.eq(Long.class), arguments.capture());
		assertThat(pinId).isEqualTo(10L);
		assertThat(arguments.getValue()).containsExactly(
			42L,
			"question",
			126.9783882,
			37.5666103,
			"서울특별시 중구 세종대로 110",
			"",
			""
		);
	}
}
