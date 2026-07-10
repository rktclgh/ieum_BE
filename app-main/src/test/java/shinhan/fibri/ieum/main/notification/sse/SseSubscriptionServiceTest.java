package shinhan.fibri.ieum.main.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.main.auth.session.SessionTokenValidator;
import shinhan.fibri.ieum.main.auth.session.ValidatedAuthSession;
import shinhan.fibri.ieum.main.notification.presence.PresenceRegistry;

class SseSubscriptionServiceTest {

	private final SessionTokenValidator sessionTokenValidator = mock(SessionTokenValidator.class);
	private final SseConnectionRegistry registry = mock(SseConnectionRegistry.class);
	private final SseEmitterFactory emitterFactory = mock(SseEmitterFactory.class);
	private final SseInitialFrameWriter initialFrameWriter = mock(SseInitialFrameWriter.class);
	private final PresenceRegistry presenceRegistry = mock(PresenceRegistry.class);
	private final SseSubscriptionService service = new SseSubscriptionService(
		sessionTokenValidator,
		registry,
		emitterFactory,
		initialFrameWriter,
		properties(),
		presenceRegistry
	);

	@Test
	void sendsInitialFrameBeforeRegisteringValidatedSession() throws Exception {
		SseEmitter emitter = mock(SseEmitter.class);
		when(sessionTokenValidator.validateSession("access-token"))
			.thenReturn(Optional.of(validatedSession()));
		when(emitterFactory.create(1_800_000L)).thenReturn(emitter);
		when(registry.register(42L, "sid-1", emitter)).thenReturn(true);

		SseEmitter result = service.subscribe("access-token");

		ArgumentCaptor<Long> retryMs = ArgumentCaptor.forClass(Long.class);
		InOrder order = inOrder(initialFrameWriter, registry, presenceRegistry);
		order.verify(initialFrameWriter).write(eq(emitter), retryMs.capture());
		order.verify(registry).register(42L, "sid-1", emitter);
		order.verify(presenceRegistry).seedOnConnect(42L);
		assertThat(result).isSameAs(emitter);
		assertThat(retryMs.getValue()).isBetween(3_000L, 8_000L);
	}

	@Test
	void doesNotSeedPresenceWhenRegistryRejectsConnectionDuringShutdown() {
		SseEmitter emitter = mock(SseEmitter.class);
		when(sessionTokenValidator.validateSession("access-token"))
			.thenReturn(Optional.of(validatedSession()));
		when(emitterFactory.create(1_800_000L)).thenReturn(emitter);
		when(registry.register(42L, "sid-1", emitter)).thenReturn(false);

		assertThat(service.subscribe("access-token")).isSameAs(emitter);

		verify(presenceRegistry, never()).seedOnConnect(42L);
	}

	@Test
	void removesOnlyRegisteredEmitterWhenPresenceSeedFails() {
		SseEmitter emitter = mock(SseEmitter.class);
		when(sessionTokenValidator.validateSession("access-token"))
			.thenReturn(Optional.of(validatedSession()));
		when(emitterFactory.create(1_800_000L)).thenReturn(emitter);
		when(registry.register(42L, "sid-1", emitter)).thenReturn(true);
		doThrow(new IllegalStateException("presence unavailable"))
			.when(presenceRegistry).seedOnConnect(42L);

		assertThatThrownBy(() -> service.subscribe("access-token"))
			.isInstanceOf(IllegalStateException.class)
			.hasMessage("presence unavailable");

		verify(registry).closeEmitter(42L, "sid-1", emitter);
	}

	@Test
	void rejectsMissingOrInvalidAccessTokenBeforeCreatingEmitter() {
		when(sessionTokenValidator.validateSession("invalid-token")).thenReturn(Optional.empty());

		assertThatThrownBy(() -> service.subscribe(null))
			.isInstanceOf(SseAuthenticationRequiredException.class);
		assertThatThrownBy(() -> service.subscribe("invalid-token"))
			.isInstanceOf(SseAuthenticationRequiredException.class);

		verify(emitterFactory, never()).create(anyLong());
		verify(registry, never()).register(
			org.mockito.ArgumentMatchers.anyLong(),
			eq("sid-1"),
			org.mockito.ArgumentMatchers.any(SseEmitter.class)
		);
	}

	@Test
	void doesNotRegisterEmitterWhenInitialFrameWriteFails() throws Exception {
		SseEmitter emitter = mock(SseEmitter.class);
		when(sessionTokenValidator.validateSession("access-token"))
			.thenReturn(Optional.of(validatedSession()));
		when(emitterFactory.create(1_800_000L)).thenReturn(emitter);
		doThrow(new IOException("connection closed"))
			.when(initialFrameWriter).write(eq(emitter), anyLong());

		assertThatThrownBy(() -> service.subscribe("access-token"))
			.isInstanceOf(SseInitialFrameWriteException.class);

		verify(emitter).completeWithError(org.mockito.ArgumentMatchers.any(IOException.class));
		verify(registry, never()).register(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(), eq(emitter));
	}

	private static NotificationProperties properties() {
		return new NotificationProperties(1_800_000L, 5, 32, 15_000L, 4, 3_000L, 8_000L, 4, 16, 500);
	}

	private static ValidatedAuthSession validatedSession() {
		return new ValidatedAuthSession(
			new AuthenticatedUser(42L, "user@example.com", UserRole.user, UserStatus.active),
			"sid-1"
		);
	}
}
