package shinhan.fibri.ieum.main.notification.presence;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;
import shinhan.fibri.ieum.main.friend.service.FriendService;
import shinhan.fibri.ieum.main.notification.sse.OutboundEvent;
import shinhan.fibri.ieum.main.notification.sse.PresenceSsePayload;
import shinhan.fibri.ieum.main.notification.sse.SseConnectionRegistry;

class FriendPresenceChangedListenerTest {

	private final FriendService friendService = mock(FriendService.class);
	private final SseConnectionRegistry registry = mock(SseConnectionRegistry.class);
	private final FriendPresenceChangedListener listener = new FriendPresenceChangedListener(friendService, registry);

	@Test
	void fansOutPresenceChangeOnlyToOnlineAcceptedFriends() {
		when(friendService.acceptedFriendIdsOf(42L)).thenReturn(Set.of(7L, 8L, 9L));
		when(registry.isOnline(7L)).thenReturn(true);
		when(registry.isOnline(8L)).thenReturn(false);
		when(registry.isOnline(9L)).thenReturn(true);

		listener.onPresenceChanged(new UserPresenceChangedEvent(42L, true));

		OutboundEvent expected = OutboundEvent.presence(new PresenceSsePayload(42L, true));
		verify(registry).push(7L, expected);
		verify(registry, never()).push(8L, expected);
		verify(registry).push(9L, expected);
	}

	@Test
	void listenerFailureDoesNotEscapeLifecyclePublication() {
		when(friendService.acceptedFriendIdsOf(42L)).thenReturn(Set.of(7L, 8L));
		when(registry.isOnline(7L)).thenReturn(true);
		doThrow(new IllegalStateException("sse unavailable"))
			.when(registry).push(7L, OutboundEvent.presence(new PresenceSsePayload(42L, false)));

		assertThatCode(() -> listener.onPresenceChanged(new UserPresenceChangedEvent(42L, false)))
			.doesNotThrowAnyException();
	}
}
