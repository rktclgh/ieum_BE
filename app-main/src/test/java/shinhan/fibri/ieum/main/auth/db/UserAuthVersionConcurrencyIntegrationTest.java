package shinhan.fibri.ieum.main.auth.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import shinhan.fibri.ieum.common.auth.domain.User;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresDataSource;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserAuthVersionConcurrencyIntegrationTest {

	private static final String DATABASE = "ieum_user_auth_version_concurrency";

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		CanonicalPostgresDataSource.recreateAndRegister(registry, DATABASE);
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
	}

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Autowired
	private JdbcTemplate jdbc;

	private long userId;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
		userId = jdbc.queryForObject("""
			INSERT INTO users(
				email, password_hash, nickname, birth_date, gender, nationality, email_verified
			) VALUES (
				'concurrent-auth@example.com', 'hash', 'before', DATE '1995-05-20',
				'female', 'KR', true
			)
			RETURNING user_id
			""", Long.class);
	}

	@Test
	void staleProfileUpdatePreservesNewerCanonicalAuthorizationState() throws Exception {
		CountDownLatch profileLoaded = new CountDownLatch(1);
		CountDownLatch authorizationCommitted = new CountDownLatch(1);
		TransactionTemplate transactions = new TransactionTemplate(transactionManager);

		try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
			Future<?> profileUpdate = executor.submit(() -> transactions.executeWithoutResult(status -> {
				User staleProfile = userRepository.findByIdAndDeletedAtIsNull(userId).orElseThrow();
				profileLoaded.countDown();
				await(authorizationCommitted, "authorization transaction did not commit");
				staleProfile.updateProfile(
					"after",
					staleProfile.getBirthDate(),
					staleProfile.getGender(),
					staleProfile.getNationality()
				);
			}));

			await(profileLoaded, "profile transaction did not load the stale user");
			try {
				transactions.executeWithoutResult(status -> {
					User canonical = userRepository.findByIdForUpdate(userId).orElseThrow();
					canonical.suspend();
					canonical.changeRole(UserRole.admin);
				});
			} finally {
				authorizationCommitted.countDown();
			}

			profileUpdate.get(5, TimeUnit.SECONDS);
		}

		Map<String, Object> row = jdbc.queryForMap("""
			SELECT nickname, status::text AS status, role::text AS role, auth_version
			FROM users
			WHERE user_id = ?
			""", userId);
		assertThat(row)
			.containsEntry("nickname", "after")
			.containsEntry("status", "suspended")
			.containsEntry("role", "admin")
			.containsEntry("auth_version", 2L);
	}

	private static void await(CountDownLatch latch, String timeoutMessage) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError(timeoutMessage);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("interrupted while waiting for concurrent transaction", exception);
		}
	}
}
