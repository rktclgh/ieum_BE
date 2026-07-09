package shinhan.fibri.ieum.main.pin.service;

import java.util.List;
import java.util.UUID;
import java.time.ZoneOffset;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.dto.CursorPage;
import shinhan.fibri.ieum.main.pin.dto.PinItem;
import shinhan.fibri.ieum.main.pin.dto.PinListRequest;
import shinhan.fibri.ieum.main.pin.dto.PinLocation;
import shinhan.fibri.ieum.main.pin.dto.PinMapRequest;
import shinhan.fibri.ieum.main.pin.dto.PinMapResponse;
import shinhan.fibri.ieum.main.pin.exception.InvalidPinRequestException;
import shinhan.fibri.ieum.main.pin.repository.PinProjection;
import shinhan.fibri.ieum.main.pin.repository.PinRepository;

@Service
@RequiredArgsConstructor
public class PinService {

	private static final int MAP_LIMIT = 500;
	private static final int DEFAULT_LIST_SIZE = 20;

	private final PinRepository pinRepository;

	@Transactional(readOnly = true)
	public PinMapResponse getMapPins(AuthenticatedUser principal, PinMapRequest request) {
		validateBbox(request);
		List<PinProjection> rows = pinRepository.findMapPins(
			principal.userId(),
			typeValue(request.type()),
			request.swLat(),
			request.swLng(),
			request.neLat(),
			request.neLng(),
			MAP_LIMIT + 1
		);
		boolean truncated = rows.size() > MAP_LIMIT;
		List<PinItem> items = rows.stream()
			.limit(MAP_LIMIT)
			.map(this::toItem)
			.toList();
		return new PinMapResponse(items, truncated);
	}

	@Transactional(readOnly = true)
	public CursorPage<PinItem> getListPins(AuthenticatedUser principal, PinListRequest request) {
		int size = request.size() == null ? DEFAULT_LIST_SIZE : request.size();
		Long cursorId = PinCursor.decode(request.cursor());
		List<PinProjection> rows = pinRepository.findListPins(
			principal.userId(),
			typeValue(request.type()),
			cursorId,
			size + 1
		);
		boolean hasNext = rows.size() > size;
		List<PinItem> items = rows.stream()
			.limit(size)
			.map(this::toItem)
			.toList();
		String nextCursor = hasNext ? PinCursor.encode(items.getLast().pinId()) : null;
		return new CursorPage<>(items, nextCursor);
	}

	private void validateBbox(PinMapRequest request) {
		if (request.swLat() >= request.neLat() || request.swLng() >= request.neLng()) {
			throw new InvalidPinRequestException("INVALID_BBOX", "bbox", "Invalid bbox");
		}
	}

	private PinItem toItem(PinProjection row) {
		return new PinItem(
			row.getPinId(),
			PinType.valueOf(row.getPinType()),
			row.getTargetId(),
			row.getTitle(),
			thumbnailUrl(row.getThumbnailFileId()),
			new PinLocation(row.getLatitude(), row.getLongitude()),
			Boolean.TRUE.equals(row.getMine()),
			row.getCreatedAt().atOffset(ZoneOffset.UTC)
		);
	}

	private String thumbnailUrl(UUID fileId) {
		if (fileId == null) {
			return null;
		}
		return "/api/v1/files/%s?v=thumb".formatted(fileId);
	}

	private String typeValue(PinType type) {
		return type == null ? null : type.name();
	}
}
