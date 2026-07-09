package shinhan.fibri.ieum.main.pin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.pin.dto.CursorPage;
import shinhan.fibri.ieum.main.pin.dto.PinItem;
import shinhan.fibri.ieum.main.pin.dto.PinListRequest;
import shinhan.fibri.ieum.main.pin.dto.PinMapRequest;
import shinhan.fibri.ieum.main.pin.dto.PinMapResponse;
import shinhan.fibri.ieum.main.pin.domain.PinType;
import shinhan.fibri.ieum.main.pin.exception.InvalidPinRequestException;
import shinhan.fibri.ieum.main.pin.repository.PinProjection;
import shinhan.fibri.ieum.main.pin.repository.PinRepository;

class PinServiceTest {

	private final PinRepository pinRepository = mock(PinRepository.class);
	private final PinService service = new PinService(pinRepository);

	@Test
	void getMapPinsTrimsLimitAndMarksTruncated() {
		List<PinProjection> rows = java.util.stream.LongStream.rangeClosed(1, 501)
			.mapToObj(id -> projection(id, "question", "title-" + id, null, 37.5, 127.0, false))
			.toList();
		when(pinRepository.findMapPins(42L, null, 37.0, 126.0, 38.0, 128.0, 501))
			.thenReturn(rows);

		PinMapResponse response = service.getMapPins(
			principal(),
			new PinMapRequest(37.0, 126.0, 38.0, 128.0, null)
		);

		assertThat(response.items()).hasSize(500);
		assertThat(response.truncated()).isTrue();
		assertThat(response.items().getFirst().pinId()).isEqualTo(1L);
		verify(pinRepository).findMapPins(42L, null, 37.0, 126.0, 38.0, 128.0, 501);
	}

	@Test
	void getMapPinsMapsThumbnailAndLocation() {
		UUID thumbnailFileId = UUID.fromString("00000000-0000-0000-0000-000000000123");
		when(pinRepository.findMapPins(42L, "meeting", 37.0, 126.0, 38.0, 128.0, 501))
			.thenReturn(List.of(projection(10L, "meeting", "coffee", thumbnailFileId, 37.55, 126.98, true)));

		PinMapResponse response = service.getMapPins(
			principal(),
			new PinMapRequest(37.0, 126.0, 38.0, 128.0, PinType.meeting)
		);

		PinItem item = response.items().getFirst();
		assertThat(item.pinId()).isEqualTo(10L);
		assertThat(item.pinType()).isEqualTo(PinType.meeting);
		assertThat(item.targetId()).isEqualTo(300L);
		assertThat(item.thumbnailUrl()).isEqualTo("/api/v1/files/%s?v=thumb".formatted(thumbnailFileId));
		assertThat(item.location().latitude()).isEqualTo(37.55);
		assertThat(item.location().longitude()).isEqualTo(126.98);
		assertThat(item.mine()).isTrue();
	}

	@Test
	void getMapPinsRejectsInvertedBbox() {
		assertThatThrownBy(() -> service.getMapPins(
			principal(),
			new PinMapRequest(38.0, 126.0, 37.0, 128.0, null)
		)).isInstanceOf(InvalidPinRequestException.class)
			.hasMessage("Invalid bbox");
	}

	@Test
	void getListPinsUsesLookaheadAndNextCursor() {
		when(pinRepository.findListPins(42L, "question", null, 3))
			.thenReturn(List.of(
				projection(30L, "question", "a", null, 37.1, 127.1, false),
				projection(20L, "question", "b", null, 37.2, 127.2, false),
				projection(10L, "question", "c", null, 37.3, 127.3, false)
			));

		CursorPage<PinItem> response = service.getListPins(
			principal(),
			new PinListRequest(PinType.question, null, 2)
		);

		assertThat(response.items()).extracting(PinItem::pinId).containsExactly(30L, 20L);
		assertThat(PinCursor.decode(response.nextCursor())).isEqualTo(20L);
		verify(pinRepository).findListPins(42L, "question", null, 3);
	}

	@Test
	void getListPinsDecodesCursorAndUsesDefaultSize() {
		String cursor = PinCursor.encode(100L);
		when(pinRepository.findListPins(42L, null, 100L, 21)).thenReturn(List.of());

		CursorPage<PinItem> response = service.getListPins(
			principal(),
			new PinListRequest(null, cursor, null)
		);

		assertThat(response.items()).isEmpty();
		assertThat(response.nextCursor()).isNull();
		verify(pinRepository).findListPins(42L, null, 100L, 21);
	}

	private static AuthenticatedUser principal() {
		return new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active);
	}

	private static PinProjection projection(
		Long pinId,
		String pinType,
		String title,
		UUID thumbnailFileId,
		double latitude,
		double longitude,
		boolean mine
	) {
		Instant createdAt = Instant.parse("2026-07-08T01:00:00Z");
		return new PinProjection() {
			@Override
			public Long getPinId() {
				return pinId;
			}

			@Override
			public String getPinType() {
				return pinType;
			}

			@Override
			public Long getTargetId() {
				return pinId * 30;
			}

			@Override
			public String getTitle() {
				return title;
			}

			@Override
			public UUID getThumbnailFileId() {
				return thumbnailFileId;
			}

			@Override
			public Double getLatitude() {
				return latitude;
			}

			@Override
			public Double getLongitude() {
				return longitude;
			}

			@Override
			public Boolean getMine() {
				return mine;
			}

			@Override
			public Instant getCreatedAt() {
				return createdAt;
			}
		};
	}
}
