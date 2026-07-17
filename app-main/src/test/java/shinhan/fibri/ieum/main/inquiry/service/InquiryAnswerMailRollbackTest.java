package shinhan.fibri.ieum.main.inquiry.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import shinhan.fibri.ieum.main.mail.AfterCommitMailTaskScheduler;

class InquiryAnswerMailRollbackTest {

	@Test
	void invokesTheMailSenderOnlyAfterTheAnswerTransactionCommits() {
		InquiryAnswerMailSender mailSender = mock(InquiryAnswerMailSender.class);
		InquiryAnswerMailEventPublisher eventPublisher = new AfterCommitInquiryAnswerMailPublisher(
			new AfterCommitMailTaskScheduler(Runnable::run),
			mailSender
		);
		TransactionSynchronizationManager.initSynchronization();
		try {
			eventPublisher.publish(event());

			verify(mailSender, never()).send(
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyLong(),
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.any(OffsetDateTime.class),
				org.mockito.ArgumentMatchers.any(Locale.class)
			);
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);

			verify(mailSender).send(
				"user@example.com",
				90L,
				"문의 제목",
				"답변 내용",
				OffsetDateTime.parse("2026-07-16T15:00:00+09:00"),
				Locale.KOREAN
			);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void doesNotInvokeTheMailSenderWhenTheAnswerTransactionRollsBack() {
		InquiryAnswerMailSender mailSender = mock(InquiryAnswerMailSender.class);
		InquiryAnswerMailEventPublisher eventPublisher = new AfterCommitInquiryAnswerMailPublisher(
			new AfterCommitMailTaskScheduler(Runnable::run),
			mailSender
		);
		TransactionSynchronizationManager.initSynchronization();
		try {
			eventPublisher.publish(event());
			TransactionSynchronizationManager.getSynchronizations().forEach(
				synchronization -> synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
			);

			verify(mailSender, never()).send(
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyLong(),
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.anyString(),
				org.mockito.ArgumentMatchers.any(OffsetDateTime.class),
				org.mockito.ArgumentMatchers.any(Locale.class)
			);
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	private InquiryAnswerMailEvent event() {
		return new InquiryAnswerMailEvent(
			"user@example.com",
			90L,
			"문의 제목",
			"답변 내용",
			OffsetDateTime.parse("2026-07-16T15:00:00+09:00"),
			Locale.KOREAN
		);
	}

}
