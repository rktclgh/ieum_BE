package shinhan.fibri.ieum.main.notification.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PresenceRegistryTest {

	@Test
	void seedsSnapshotIncludingNullLocationAndRemovesItOnDisconnect() {
		PresenceSeedRepository repository = mock(PresenceSeedRepository.class);
		PresenceRegistry registry = new PresenceRegistry(repository);
		when(repository.findSeedByUserId(42L)).thenReturn(Optional.of(new PresenceSeed(null, null, true, true, false, 5)));

		registry.seedOnConnect(42L);

		assertThat(registry.findByUserId(42L)).contains(new PresenceSnapshot(null, null, true, true, false, 5));
		registry.removeOnLastDisconnect(42L);
		assertThat(registry.findByUserId(42L)).isEmpty();
	}

	@Test
	void findsOnlyNearbyPrecisionFiveGeoHashCandidates() {
		PresenceSeedRepository repository = mock(PresenceSeedRepository.class);
		PresenceRegistry registry = new PresenceRegistry(repository);
		when(repository.findSeedByUserId(1L)).thenReturn(Optional.of(new PresenceSeed(37.5665, 126.9780, true, true, true, 5)));
		when(repository.findSeedByUserId(2L)).thenReturn(Optional.of(new PresenceSeed(37.5700, 126.9780, true, true, true, 5)));
		when(repository.findSeedByUserId(3L)).thenReturn(Optional.of(new PresenceSeed(37.9000, 126.9780, true, true, true, 5)));
		registry.seedOnConnect(1L);
		registry.seedOnConnect(2L);
		registry.seedOnConnect(3L);

		assertThat(registry.nearbyUserIds(37.5665, 126.9780, 3)).isEqualTo(Set.of(1L, 2L));
	}

	@Test
	void exposesOnlineUserIdsForFullScanFallback() {
		PresenceSeedRepository repository = mock(PresenceSeedRepository.class);
		PresenceRegistry registry = new PresenceRegistry(repository);
		when(repository.findSeedByUserId(1L)).thenReturn(Optional.of(new PresenceSeed(null, null, true, true, true, 5)));
		when(repository.findSeedByUserId(2L)).thenReturn(Optional.of(new PresenceSeed(null, null, true, true, true, 5)));
		registry.seedOnConnect(1L);
		registry.seedOnConnect(2L);

		assertThat(registry.allUserIds()).containsExactlyInAnyOrder(1L, 2L);
	}
}
