package shinhan.fibri.ieum.main.admin.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import jakarta.persistence.LockModeType;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import shinhan.fibri.ieum.common.auth.domain.UserRole;
import shinhan.fibri.ieum.common.auth.domain.UserStatus;
import shinhan.fibri.ieum.common.auth.principal.AuthenticatedUser;
import shinhan.fibri.ieum.common.auth.repository.UserRepository;
import shinhan.fibri.ieum.main.admin.audit.repository.AdminAuditLogWriter;
import shinhan.fibri.ieum.main.admin.user.exception.AdminRoleRequiredException;
import shinhan.fibri.ieum.main.admin.user.exception.LastAdminRequiredException;
import shinhan.fibri.ieum.main.auth.session.RedisAuthSessionStore;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;
import shinhan.fibri.ieum.testsupport.SqlScriptRunner;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({AdminUserRoleService.class, AdminUserRoleConcurrencyIntegrationTest.AdminLockBarrierConfiguration.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AdminUserRoleConcurrencyIntegrationTest {

	private static final String DATABASE = "ieum_admin_role_concurrency";

	static {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		SqlScriptRunner.run(DATABASE, "schema.sql");
	}

	@DynamicPropertySource
	static void registerDataSourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", () -> CanonicalPostgresContainer.jdbcUrl(DATABASE));
		registry.add("spring.datasource.username", CanonicalPostgresContainer::username);
		registry.add("spring.datasource.password", CanonicalPostgresContainer::password);
		registry.add("spring.datasource.driver-class-name", CanonicalPostgresContainer::driverClassName);
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "4");
		registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
		registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET lock_timeout = '5s'");
		registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
		registry.add(
			"spring.jpa.properties.hibernate.dialect",
			() -> "shinhan.fibri.ieum.common.config.SnakeCasePostgreSQLDialect"
		);
	}

	@Autowired
	private AdminUserRoleService service;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private AdminLockBarrier adminLockBarrier;

	@MockitoBean
	private RedisAuthSessionStore sessionStore;

	@MockitoBean
	private AdminAuditLogWriter auditLogWriter;

	private long firstAdminId;
	private long secondAdminId;

	@AfterAll
	static void cleanUpDatabase() {
		JdbcTemplate admin = new JdbcTemplate(CanonicalPostgresContainer.dataSource("postgres"));
		admin.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
	}

	@BeforeEach
	void setUp() {
		jdbc.execute("TRUNCATE TABLE users RESTART IDENTITY CASCADE");
		firstAdminId = insertAdmin("first-admin@example.com", "first-admin");
		secondAdminId = insertAdmin("second-admin@example.com", "second-admin");
	}

	@Test
	void secondReciprocalDemotionWaitsForFirstAdminLockBeforeStaleActorRejection() throws Exception {
		AtomicInteger lockQuerySequence = new AtomicInteger();
		CountDownLatch firstAdminLocksAcquired = new CountDownLatch(1);
		CountDownLatch releaseFirstRoleChange = new CountDownLatch(1);
		CountDownLatch secondAdminQueryEntered = new CountDownLatch(1);
		CountDownLatch secondAdminLocksAcquired = new CountDownLatch(1);
		adminLockBarrier.install(
			lockQuerySequence,
			firstAdminLocksAcquired,
			releaseFirstRoleChange,
			secondAdminQueryEntered,
			secondAdminLocksAcquired
		);

		ExecutorService executor = newDaemonExecutor();
		Future<RoleChangeAttempt> first = null;
		Future<RoleChangeAttempt> second = null;
		try {
			first = executor.submit(() -> demote(firstAdminId, secondAdminId));
			awaitOrFail(firstAdminLocksAcquired, "first role change did not acquire the administrator locks");

			second = executor.submit(() -> demote(secondAdminId, firstAdminId));
			awaitOrFail(secondAdminQueryEntered, "second role change did not enter the administrator lock query");
			awaitBlockedDatabaseSession();

			assertThat(secondAdminLocksAcquired.getCount()).isOne();
			assertThat(second).isNotDone();

			releaseFirstRoleChange.countDown();
			List<RoleChangeAttempt> attempts = List.of(
				first.get(10, TimeUnit.SECONDS),
				second.get(10, TimeUnit.SECONDS)
			);

			assertThat(attempts.stream().filter(RoleChangeAttempt::succeeded)).hasSize(1);
			assertThat(attempts.stream()
				.filter(attempt -> !attempt.succeeded())
				.map(RoleChangeAttempt::failureType))
				.containsExactly(AdminRoleRequiredException.class);
		} finally {
			adminLockBarrier.release();
			cancel(first);
			cancel(second);
			try {
				shutdown(executor);
			} finally {
				adminLockBarrier.reset();
			}
		}

		assertThat(activeAdminCount()).isOne();
		assertThat(jdbc.queryForObject("SELECT sum(auth_version) FROM users", Long.class)).isOne();
		verify(sessionStore, times(1)).revokeAllSessionsOfUser(anyLong());
		verify(auditLogWriter, times(1)).append(anyLong(), any(), any(), anyLong(), any());
	}

	@Test
	void adminLockRepositoryContractIsPessimisticAndOrderedByAscendingId() throws NoSuchMethodException {
		Method method = UserRepository.class.getMethod("findAllAdminsForUpdate");
		Lock lock = method.getAnnotation(Lock.class);
		Query query = method.getAnnotation(Query.class);

		assertThat(lock).isNotNull();
		assertThat(lock.value()).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
		assertThat(query).isNotNull();
		assertThat(query.value().replaceAll("\\s+", " ").trim()).contains("order by u.id asc");
	}

	@Test
	void canonicalFinalAdminCannotDemoteOwnAccount() {
		jdbc.update("UPDATE users SET role = 'user' WHERE user_id = ?", secondAdminId);

		assertThatThrownBy(() -> service.changeRole(
			principal(firstAdminId, "first-admin@example.com"),
			firstAdminId,
			UserRole.user
		)).isInstanceOf(LastAdminRequiredException.class);

		assertThat(activeAdminCount()).isOne();
		assertThat(jdbc.queryForObject(
			"SELECT auth_version FROM users WHERE user_id = ?",
			Long.class,
			firstAdminId
		)).isZero();
	}

	private RoleChangeAttempt demote(long actorId, long targetId) {
		try {
			service.changeRole(principal(actorId, "admin-%d@example.com".formatted(actorId)), targetId, UserRole.user);
			return new RoleChangeAttempt(true, null);
		} catch (RuntimeException exception) {
			return new RoleChangeAttempt(false, exception.getClass());
		}
	}

	private void awaitBlockedDatabaseSession() {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			Integer blockedSessions = jdbc.queryForObject("""
				SELECT count(*)
				  FROM pg_stat_activity activity
				 WHERE activity.datname = current_database()
				   AND cardinality(pg_blocking_pids(activity.pid)) > 0
				""", Integer.class);
			if (blockedSessions != null && blockedSessions >= 1) {
				return;
			}
			pause();
		}
		throw new AssertionError("second role change did not block on the first administrator row locks");
	}

	private long insertAdmin(String email, String nickname) {
		return jdbc.queryForObject("""
			INSERT INTO users (email, password_hash, nickname, email_verified, role)
			VALUES (?, 'hash', ?, TRUE, 'admin')
			RETURNING user_id
			""", Long.class, email, nickname);
	}

	private int activeAdminCount() {
		return jdbc.queryForObject(
			"SELECT count(*) FROM users WHERE role = 'admin' AND deleted_at IS NULL",
			Integer.class
		);
	}

	private static AuthenticatedUser principal(long userId, String email) {
		return new AuthenticatedUser(userId, email, UserRole.admin, UserStatus.active);
	}

	private static void awaitOrFail(CountDownLatch latch, String failureMessage) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError(failureMessage);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(failureMessage, exception);
		}
	}

	private static void pause() {
		try {
			Thread.sleep(20);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while observing the database lock wait", exception);
		}
	}

	private static ExecutorService newDaemonExecutor() {
		AtomicInteger threadSequence = new AtomicInteger();
		return Executors.newFixedThreadPool(2, runnable -> {
			Thread thread = new Thread(runnable, "admin-role-concurrency-" + threadSequence.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		});
	}

	private static void cancel(Future<?> future) {
		if (future != null && !future.isDone()) {
			future.cancel(true);
		}
	}

	private static void shutdown(ExecutorService executor) {
		executor.shutdownNow();
		try {
			if (!executor.awaitTermination(8, TimeUnit.SECONDS)) {
				throw new AssertionError("administrator role concurrency executor did not terminate");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while stopping administrator role concurrency executor", exception);
		}
	}

	private record RoleChangeAttempt(boolean succeeded, Class<? extends RuntimeException> failureType) {
	}

	private record BarrierState(
		AtomicInteger lockQuerySequence,
		CountDownLatch firstAdminLocksAcquired,
		CountDownLatch releaseFirstRoleChange,
		CountDownLatch secondAdminQueryEntered,
		CountDownLatch secondAdminLocksAcquired
	) {
	}

	static final class AdminLockBarrier {

		private final AtomicReference<BarrierState> state = new AtomicReference<>();

		void install(
			AtomicInteger lockQuerySequence,
			CountDownLatch firstAdminLocksAcquired,
			CountDownLatch releaseFirstRoleChange,
			CountDownLatch secondAdminQueryEntered,
			CountDownLatch secondAdminLocksAcquired
		) {
			release();
			state.set(new BarrierState(
				lockQuerySequence,
				firstAdminLocksAcquired,
				releaseFirstRoleChange,
				secondAdminQueryEntered,
				secondAdminLocksAcquired
			));
		}

		Object intercept(MethodInvocation invocation) throws Throwable {
			if (!invocation.getMethod().getName().equals("findAllAdminsForUpdate")) {
				return invocation.proceed();
			}
			BarrierState current = state.get();
			if (current == null) {
				return invocation.proceed();
			}

			int invocationIndex = current.lockQuerySequence().incrementAndGet();
			if (invocationIndex == 2) {
				current.secondAdminQueryEntered().countDown();
			}
			Object result = invocation.proceed();
			if (invocationIndex == 1) {
				current.firstAdminLocksAcquired().countDown();
				awaitOrFail(current.releaseFirstRoleChange(), "first role change lock barrier was not released");
			} else if (invocationIndex == 2) {
				current.secondAdminLocksAcquired().countDown();
			}
			return result;
		}

		void release() {
			BarrierState current = state.get();
			if (current != null) {
				current.releaseFirstRoleChange().countDown();
			}
		}

		void reset() {
			release();
			state.set(null);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class AdminLockBarrierConfiguration {

		@Bean
		AdminLockBarrier adminLockBarrier() {
			return new AdminLockBarrier();
		}

		@Bean
		static BeanPostProcessor adminLockBarrierPostProcessor(AdminLockBarrier barrier) {
			return new BeanPostProcessor() {
				@Override
				public Object postProcessAfterInitialization(Object bean, String beanName) {
					if (!beanName.equals("userRepository")) {
						return bean;
					}
					ProxyFactory proxyFactory = new ProxyFactory(bean);
					proxyFactory.addAdvice((MethodInterceptor) barrier::intercept);
					return proxyFactory.getProxy();
				}
			};
		}
	}
}
