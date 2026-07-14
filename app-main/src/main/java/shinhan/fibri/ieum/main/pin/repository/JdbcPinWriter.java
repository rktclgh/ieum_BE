package shinhan.fibri.ieum.main.pin.repository;

import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.dto.LocationSnapshot;

@Repository
@RequiredArgsConstructor
public class JdbcPinWriter implements PinWriter {

	private final JdbcTemplate jdbcTemplate;

	@Override
	public Long create(Long authorId, PinType type, LocationSnapshot location) {
		LocationSnapshot normalized = location.normalized();
		// ST_MakePoint(x, y)는 (경도, 위도) 순서를 기대하므로 longitude, latitude로 바꿔 바인딩한다.
		return jdbcTemplate.queryForObject(
			"""
				INSERT INTO pins (author_id, pin_type, location, address, detail_address, label)
				VALUES (?, CAST(? AS pin_type), ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?, ?, ?)
				RETURNING pin_id
				""",
			Long.class,
			authorId,
			type.name(),
			normalized.lng(),
			normalized.lat(),
			normalized.address(),
			normalized.detailAddress(),
			normalized.label()
		);
	}

	@Override
	public void softDelete(Long pinId, OffsetDateTime deletedAt) {
		jdbcTemplate.update(
			"""
				UPDATE pins
				   SET deleted_at = ?
				 WHERE pin_id = ?
				   AND deleted_at IS NULL
				""",
			deletedAt,
			pinId
		);
	}
}
