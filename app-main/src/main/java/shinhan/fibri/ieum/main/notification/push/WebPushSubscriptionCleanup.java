package shinhan.fibri.ieum.main.notification.push;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class WebPushSubscriptionCleanup {

	private static final Logger log = LoggerFactory.getLogger(WebPushSubscriptionCleanup.class);

	private final WebPushSubscriptionRepository repository;
	private final TransactionTemplate cleanupTransaction;

	public WebPushSubscriptionCleanup(
		WebPushSubscriptionRepository repository,
		PlatformTransactionManager transactionManager
	) {
		this.repository = repository;
		this.cleanupTransaction = new TransactionTemplate(transactionManager);
		this.cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	public void deleteForSession(String sessionId) {
		try {
			Integer deletedCount = cleanupTransaction.execute(
				status -> repository.deleteAllBySessionId(sessionId)
			);
			log.info("Web push subscription cleanup completed: scope=session count={}", count(deletedCount));
		}
		catch (RuntimeException exception) {
			log.warn(
				"Web push subscription cleanup failed: scope=session failureClass={}",
				exception.getClass().getSimpleName()
			);
		}
	}

	public void deleteForUser(long userId) {
		try {
			Integer deletedCount = cleanupTransaction.execute(
				status -> repository.deleteAllByUserId(userId)
			);
			log.info(
				"Web push subscription cleanup completed: scope=user userId={} count={}",
				userId,
				count(deletedCount)
			);
		}
		catch (RuntimeException exception) {
			log.warn(
				"Web push subscription cleanup failed: scope=user userId={} failureClass={}",
				userId,
				exception.getClass().getSimpleName()
			);
		}
	}

	private static int count(Integer deletedCount) {
		return deletedCount == null ? 0 : deletedCount;
	}
}
