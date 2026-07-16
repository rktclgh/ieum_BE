package shinhan.fibri.ieum.main.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.common.auth.domain.GenderType;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.main.admin.user.domain.UserSanction;

class AfterCommitUserSuspensionEventPublisherTest {

	@Test
	void sendsTheSuspensionMailAfterCommitWithTheStoredUserLocale() {
		UserMailLocaleResolver localeResolver = mock(UserMailLocaleResolver.class);
		UserSuspensionMailSender mailSender = mock(UserSuspensionMailSender.class);
		AfterCommitUserSuspensionEventPublisher publisher = new AfterCommitUserSuspensionEventPublisher(
			new AfterCommitMailTaskScheduler(Runnable::run),
			localeResolver,
			mailSender
		);
		User user = User.createEmailUser(
			"user@example.com",
			"hash",
			"user",
			LocalDate.of(2000, 1, 1),
			GenderType.female,
			"KR"
		);
		ReflectionTestUtils.setField(user, "id", 10L);
		UserSanction sanction = UserSanction.temporary(
			10L,
			"abusive messages",
			1L,
			OffsetDateTime.now().plusDays(3)
		);
		when(localeResolver.resolve(10L)).thenReturn(Locale.JAPANESE);

		TransactionSynchronizationManager.initSynchronization();
		try {
			publisher.publish(user, sanction);
			verify(localeResolver).resolve(10L);
			clearInvocations(localeResolver);

			verify(mailSender, never()).send(org.mockito.ArgumentMatchers.any(UserSuspensionEvent.class));
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

			var eventCaptor = forClass(UserSuspensionEvent.class);
			verify(mailSender).send(eventCaptor.capture());
			verifyNoInteractions(localeResolver);
			UserSuspensionEvent event = eventCaptor.getValue();
			assertThat(event.userId()).isEqualTo(10L);
			assertThat(event.email()).isEqualTo("user@example.com");
			assertThat(event.reason()).isEqualTo("abusive messages");
			assertThat(event.startsAt()).isEqualTo(sanction.getStartsAt());
			assertThat(event.endsAt()).isEqualTo(sanction.getEndsAt());
			assertThat(event.locale()).isEqualTo(Locale.JAPANESE);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}
}
