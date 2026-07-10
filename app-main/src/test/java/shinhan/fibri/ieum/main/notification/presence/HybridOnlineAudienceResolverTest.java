package shinhan.fibri.ieum.main.notification.presence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HybridOnlineAudienceResolverTest {

	@Test
	void resolvesOnlyNearbyEnabledUnblockedUsersOtherThanAuthor() {
		PresenceSeedRepository repository = mock(PresenceSeedRepository.class);
		PresenceRegistry registry = new PresenceRegistry(repository);
		seed(repository, registry, 1L, 37.5665, 126.9780, true, true, true, 5);
		seed(repository, registry, 2L, 37.5700, 126.9780, true, true, true, 5);
		seed(repository, registry, 3L, 37.5700, 126.9780, true, false, true, 5);
		seed(repository, registry, 4L, 37.5700, 126.9780, true, true, true, 5);
		seed(repository, registry, 5L, 37.7000, 126.9780, true, true, true, 3);

		HybridOnlineAudienceResolver resolver = new HybridOnlineAudienceResolver(registry);

		assertThat(resolver.resolve(37.5665, 126.9780, NotificationCategory.question, 1L, Set.of(4L)))
			.containsExactly(2L);
	}

	@Test
	void usesFullScanFallbackForEventOutsideKoreaFastPath() {
		PresenceSeedRepository repository = mock(PresenceSeedRepository.class);
		PresenceRegistry registry = new PresenceRegistry(repository);
		seed(repository, registry, 2L, 40.7128, -74.0060, true, true, true, 5);

		assertThat(new HybridOnlineAudienceResolver(registry)
			.resolve(40.7128, -74.0060, NotificationCategory.question, 1L, Set.of()))
			.containsExactly(2L);
	}

	private static void seed(PresenceSeedRepository repository, PresenceRegistry registry, Long userId, double latitude, double longitude, boolean all, boolean question, boolean meeting, int radius) {
		when(repository.findSeedByUserId(userId)).thenReturn(Optional.of(new PresenceSeed(latitude, longitude, all, question, meeting, radius)));
		registry.seedOnConnect(userId);
	}
}
