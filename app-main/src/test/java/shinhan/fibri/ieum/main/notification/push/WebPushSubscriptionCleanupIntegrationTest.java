package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;

import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@Testcontainers(disabledWithoutDocker = true)
class WebPushSubscriptionCleanupIntegrationTest {

	private static final String DATABASE = "ieum_web_push_cleanup";

	private JdbcClient jdbc;
	private WebPushSubscriptionRepository repository;
	private WebPushSubscriptionCleanup cleanup;
	private TransactionTemplate transaction;

	@BeforeEach
	void setUp() {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
		DataSource dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		PlatformTransactionManager transactionManager = new DataSourceTransactionManager(dataSource);
		jdbc = JdbcClient.create(dataSource);
		repository = new JdbcWebPushSubscriptionRepository(jdbc);
		cleanup = new WebPushSubscriptionCleanup(repository, transactionManager);
		transaction = new TransactionTemplate(transactionManager);
		insertUser();
		transaction.execute(status -> repository.upsert(new WebPushSubscriptionInput(
			42L,
			"session-42",
			"https://push.example/subscriptions/cleanup",
			"p256dh",
			"auth",
			null
		)));
	}

	@Test
	void cleanupInvokedFromOuterAfterCommitDurablyDeletesSubscription() {
		transaction.executeWithoutResult(status -> {
			assertThat(repository.existsActiveByUserIdAndSessionId(42L, "session-42")).isTrue();
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					cleanup.deleteForSession("session-42");
				}
			});
		});

		assertThat(repository.existsActiveByUserIdAndSessionId(42L, "session-42")).isFalse();
		assertThat(subscriptionCount()).isZero();
	}

	private void insertUser() {
		jdbc.sql("""
			INSERT INTO users (user_id, email, password_hash, nickname, email_verified)
			VALUES (42, 'cleanup@example.com', 'hash', 'cleanup-user', true)
			""").update();
	}

	private int subscriptionCount() {
		return jdbc.sql("SELECT count(*)::integer FROM web_push_subscriptions")
			.query(Integer.class)
			.single();
	}
}
