package shinhan.fibri.ieum.main.chat.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import shinhan.fibri.ieum.common.chat.domain.RoomType;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomListEvent;
import shinhan.fibri.ieum.main.chat.dto.ChatRoomSummaryResponse;

class ChatRoomListChangeListenerTest {

	private final ChatRoomSummaryQueryService summaryQueryService = org.mockito.Mockito.mock(ChatRoomSummaryQueryService.class);
	private final ChatRoomListEventPublisher publisher = org.mockito.Mockito.mock(ChatRoomListEventPublisher.class);
	private final PlatformTransactionManager transactionManager = org.mockito.Mockito.mock(PlatformTransactionManager.class);
	private final ChatRoomListChangeListener listener = new ChatRoomListChangeListener(
		summaryQueryService,
		publisher,
		transactionManager
	);

	@BeforeEach
	void setUp() {
		when(transactionManager.getTransaction(ArgumentMatchers.any()))
			.thenReturn(new SimpleTransactionStatus());
	}

	@Test
	void upsertPublishesOnePersonalizedSummaryPerRequestedActiveUser() {
		ChatRoomSummaryResponse meSummary = summary(100L, 2L);
		ChatRoomSummaryResponse friendSummary = summary(100L, 0L);
		when(summaryQueryService.findActiveForRoomAndUsers(100L, List.of(42L, 77L, 88L)))
			.thenReturn(Map.of(42L, meSummary, 77L, friendSummary));

		listener.handle(ChatRoomListChangeEvent.upsert(100L, List.of(42L, 77L, 88L)));

		verify(publisher).publish(42L, ChatRoomListEvent.upsert(meSummary));
		verify(publisher).publish(77L, ChatRoomListEvent.upsert(friendSummary));
	}

	@Test
	void upsertContinuesPublishingRemainingUsersWhenOneBrokerPublishFails() {
		ChatRoomSummaryResponse meSummary = summary(100L, 2L);
		ChatRoomSummaryResponse friendSummary = summary(100L, 0L);
		Map<Long, ChatRoomSummaryResponse> summaries = new LinkedHashMap<>();
		summaries.put(42L, meSummary);
		summaries.put(77L, friendSummary);
		when(summaryQueryService.findActiveForRoomAndUsers(100L, List.of(42L, 77L)))
			.thenReturn(summaries);
		doThrow(new RuntimeException("broker failed"))
			.when(publisher)
			.publish(42L, ChatRoomListEvent.upsert(meSummary));

		assertThatCode(() -> listener.handle(ChatRoomListChangeEvent.upsert(100L, List.of(42L, 77L))))
			.doesNotThrowAnyException();

		verify(publisher).publish(42L, ChatRoomListEvent.upsert(meSummary));
		verify(publisher).publish(77L, ChatRoomListEvent.upsert(friendSummary));
	}

	@Test
	void removePublishesOnlyCapturedRequestedUsers() {
		listener.handle(ChatRoomListChangeEvent.remove(100L, List.of(42L, 77L)));

		verify(publisher).publish(42L, ChatRoomListEvent.remove(100L));
		verify(publisher).publish(77L, ChatRoomListEvent.remove(100L));
		verifyNoInteractions(summaryQueryService);
	}

	@Test
	void removeContinuesPublishingRemainingUsersWhenOneBrokerPublishFails() {
		doThrow(new RuntimeException("broker failed"))
			.when(publisher)
			.publish(42L, ChatRoomListEvent.remove(100L));

		assertThatCode(() -> listener.handle(ChatRoomListChangeEvent.remove(100L, List.of(42L, 77L))))
			.doesNotThrowAnyException();

		verify(publisher).publish(42L, ChatRoomListEvent.remove(100L));
		verify(publisher).publish(77L, ChatRoomListEvent.remove(100L));
		verifyNoInteractions(summaryQueryService);
	}

	@Test
	void upsertWithNoActiveMembersIsSilent() {
		when(summaryQueryService.findActiveForRoomAndUsers(100L, List.of(42L)))
			.thenReturn(Map.of());

		listener.handle(ChatRoomListChangeEvent.upsert(100L, List.of(42L)));

		verifyNoInteractions(publisher);
	}

	@Test
	void runtimeFailureDoesNotEscapeAfterCommitListener() {
		when(summaryQueryService.findActiveForRoomAndUsers(100L, List.of(42L)))
			.thenThrow(new RuntimeException("read failed"));

		assertThatCode(() -> listener.handle(ChatRoomListChangeEvent.upsert(100L, List.of(42L))))
			.doesNotThrowAnyException();
		verifyNoInteractions(publisher);
	}

	private ChatRoomSummaryResponse summary(Long roomId, long unreadCount) {
		return new ChatRoomSummaryResponse(roomId, RoomType.direct, null, null, false, true, unreadCount, null);
	}
}
