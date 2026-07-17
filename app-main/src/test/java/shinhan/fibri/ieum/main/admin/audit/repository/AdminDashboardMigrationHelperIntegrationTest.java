package shinhan.fibri.ieum.main.admin.audit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.Container;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Testcontainers;
import shinhan.fibri.ieum.testsupport.CanonicalPostgresContainer;

@Testcontainers(disabledWithoutDocker = true)
class AdminDashboardMigrationHelperIntegrationTest {

	private static final String DATABASE = "ieum_admin_migration";
	private static final String CONTAINER_ROOT = "/tmp/ieum-admin-dashboard-migration";
	private static final String ADVISORY_LOCK_NAME = "ieum:admin-dashboard:v25-v26";

	private JdbcClient jdbc;
	private DataSource dataSource;

	@BeforeEach
	void recreateDatabaseAndCopyScripts() throws Exception {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		dataSource = CanonicalPostgresContainer.dataSource(DATABASE);
		jdbc = JdbcClient.create(dataSource);
		jdbc.sql("CREATE TABLE users (user_id BIGSERIAL PRIMARY KEY)").update();
		jdbc.sql("""
			CREATE TABLE messages (
				message_id BIGSERIAL PRIMARY KEY,
				room_id BIGINT NOT NULL,
				sender_id BIGINT NOT NULL,
				content TEXT,
				image_file_id UUID,
				CHECK (content IS NOT NULL OR image_file_id IS NOT NULL)
			)
			""").update();
		copyMigrationFiles();
	}

	@AfterAll
	static void cleanUpDatabase() {
		JdbcClient admin = JdbcClient.create(CanonicalPostgresContainer.dataSource("postgres"));
		admin.sql("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)").update();
	}

	@Test
	void firstApplyAndRetryBothVerifyTheExactSchema() throws Exception {
		Container.ExecResult first = runHelper();
		Container.ExecResult retry = runHelper();

		assertSuccessful(first);
		assertSuccessful(retry);
		assertThat(jdbc.sql("SELECT auth_version FROM users LIMIT 1").query(Long.class).optional())
			.isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM admin_audit_logs").query(Long.class).single())
			.isZero();
	}

	@Test
	void partialAuditSchemaFailsWithoutApplyingAuthMigration() throws Exception {
		jdbc.sql("CREATE TABLE admin_audit_logs (audit_id BIGINT PRIMARY KEY)").update();

		Container.ExecResult result = runHelper();

		assertThat(result.getExitCode()).isNotZero();
		assertThat(result.getStderr())
			.contains("partial or incompatible admin_audit_logs schema");
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM information_schema.columns
			WHERE table_schema = 'public'
			  AND table_name = 'users'
			  AND column_name = 'auth_version'
			""").query(Long.class).single()).isZero();
	}

	@Test
	void partialAuthSchemaFailsBeforeCreatingAuditStorage() throws Exception {
		jdbc.sql("ALTER TABLE users ADD COLUMN auth_version BIGINT").update();

		Container.ExecResult result = runHelper();

		assertThat(result.getExitCode()).isNotZero();
		assertThat(result.getStderr())
			.contains("partial or incompatible users.auth_version schema");
		assertThat(jdbc.sql("SELECT to_regclass('public.admin_audit_logs') IS NULL")
			.query(Boolean.class).single()).isTrue();
	}

	@Test
	void reservedAuthConstraintNameCollisionFailsWhileColumnIsAbsent() throws Exception {
		jdbc.sql("""
			ALTER TABLE users
			ADD CONSTRAINT ck_users_auth_version_nonnegative CHECK (user_id > 0)
			""").update();

		Container.ExecResult result = runHelper();

		assertMismatch(result, "partial or incompatible users.auth_version schema");
		assertThat(authVersionColumnCount()).isZero();
		assertThat(jdbc.sql("""
			SELECT count(*)
			FROM pg_constraint
			WHERE conrelid = 'public.users'::regclass
			  AND conname = 'ck_users_auth_version_nonnegative'
			  AND pg_get_expr(conbin, conrelid) = '(user_id > 0)'
			""").query(Long.class).single()).isOne();
	}

	@Test
	void permissiveActionCheckWithOrTrueFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("ALTER TABLE admin_audit_logs DROP CONSTRAINT ck_admin_audit_logs_action").update();
		jdbc.sql("""
			ALTER TABLE admin_audit_logs
			ADD CONSTRAINT ck_admin_audit_logs_action CHECK (
				action IN (
					'USER_SANCTION_CREATED',
					'USER_ACTIVATED',
					'USER_ROLE_CHANGED',
					'REPORT_CONFIRMED',
					'REPORT_DISMISSED',
					'INQUIRY_ANSWERED'
				) OR TRUE
			)
			""").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void actionCheckWithAnExtraAllowedValueFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("ALTER TABLE admin_audit_logs DROP CONSTRAINT ck_admin_audit_logs_action").update();
		jdbc.sql("""
			ALTER TABLE admin_audit_logs
			ADD CONSTRAINT ck_admin_audit_logs_action CHECK (
				action IN (
					'USER_SANCTION_CREATED',
					'USER_ACTIVATED',
					'USER_ROLE_CHANGED',
					'REPORT_CONFIRMED',
					'REPORT_DISMISSED',
					'INQUIRY_ANSWERED',
					'UNEXPECTED_ACTION'
				)
			)
			""").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void wrongPrimaryKeyColumnArrayFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("ALTER TABLE admin_audit_logs DROP CONSTRAINT admin_audit_logs_pkey").update();
		jdbc.sql("""
			ALTER TABLE admin_audit_logs
			ADD CONSTRAINT admin_audit_logs_pkey PRIMARY KEY (audit_id, target_id)
			""").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void deferrableActorForeignKeyFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("""
			ALTER TABLE admin_audit_logs
			DROP CONSTRAINT admin_audit_logs_actor_user_id_fkey
			""").update();
		jdbc.sql("""
			ALTER TABLE admin_audit_logs
			ADD CONSTRAINT admin_audit_logs_actor_user_id_fkey
			FOREIGN KEY (actor_user_id) REFERENCES users(user_id)
			ON DELETE SET NULL DEFERRABLE INITIALLY DEFERRED
			""").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void wrongActorForeignKeyShapeFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("ALTER TABLE users ADD COLUMN alternate_user_id BIGINT UNIQUE").update();
		jdbc.sql("""
			ALTER TABLE admin_audit_logs
			DROP CONSTRAINT admin_audit_logs_actor_user_id_fkey
			""").update();
		jdbc.sql("""
			ALTER TABLE admin_audit_logs
			ADD CONSTRAINT admin_audit_logs_actor_user_id_fkey
			FOREIGN KEY (target_id) REFERENCES users(alternate_user_id)
			MATCH FULL ON UPDATE CASCADE ON DELETE CASCADE
			""").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void partialRequiredIndexFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("DROP INDEX idx_admin_audit_logs_actor_created").update();
		jdbc.sql("""
			CREATE INDEX idx_admin_audit_logs_actor_created
			ON admin_audit_logs(actor_user_id, created_at DESC, audit_id DESC)
			WHERE actor_user_id IS NOT NULL
			""").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void invalidRequiredIndexFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("""
			UPDATE pg_index
			SET indisvalid = FALSE,
			    indisready = FALSE
			WHERE indexrelid = 'idx_admin_audit_logs_created_desc'::regclass
			""").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void uniqueRequiredIndexFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("DROP INDEX idx_admin_audit_logs_actor_created").update();
		jdbc.sql("""
			CREATE UNIQUE INDEX idx_admin_audit_logs_actor_created
			ON admin_audit_logs(actor_user_id, created_at DESC, audit_id DESC)
			""").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void unloggedAuditTableFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("ALTER TABLE admin_audit_logs SET UNLOGGED").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void detachedAuditSerialSequenceFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("ALTER SEQUENCE admin_audit_logs_audit_id_seq OWNED BY NONE").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void generatedAuditColumnFailsPreflight() throws Exception {
		createAuditSchemaWithGeneratedTargetId();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
		assertThat(authVersionColumnCount()).isZero();
	}

	@Test
	void extraAuditConstraintFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("""
			ALTER TABLE admin_audit_logs
			ADD CONSTRAINT ck_admin_audit_logs_target_positive CHECK (target_id > 0)
			""").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void extraAuditIndexFailsPreflight() throws Exception {
		assertSuccessful(runHelper());
		jdbc.sql("CREATE INDEX idx_admin_audit_logs_action ON admin_audit_logs(action)").update();

		assertMismatch(runHelper(), "partial or incompatible admin_audit_logs schema");
	}

	@Test
	void failedPreflightReleasesTheSessionAdvisoryLockImmediately() throws Exception {
		jdbc.sql("ALTER TABLE users ADD COLUMN auth_version BIGINT").update();

		assertMismatch(runHelper(), "partial or incompatible users.auth_version schema");

		try (Connection connection = dataSource.getConnection()) {
			assertThat(tryAdvisoryLock(connection)).isTrue();
			unlockAdvisoryLock(connection);
		}
	}

	@Test
	void helperBlocksOnTheHeldSessionAdvisoryLockBeforeApplyingMigrations() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Container.ExecResult> run = null;
		try (Connection barrierConnection = dataSource.getConnection()) {
			lockAdvisory(barrierConnection);
			try {
				run = executor.submit(this::runHelper);
				awaitBlockedAdvisoryLock();
				assertThat(run).isNotDone();
				assertThat(authVersionColumnCount()).isZero();
			}
			finally {
				unlockAdvisoryLock(barrierConnection);
			}

			assertSuccessful(run.get(15, TimeUnit.SECONDS));
		}
		finally {
			if (run != null && !run.isDone()) {
				run.cancel(true);
			}
			executor.shutdownNow();
			assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
		}

		assertThat(jdbc.sql("SELECT to_regclass('public.admin_audit_logs') IS NOT NULL")
			.query(Boolean.class).single()).isTrue();
	}

	private Container.ExecResult runHelper() throws Exception {
		return CanonicalPostgresContainer.instance().execInContainer(
			"bash",
			"-lc",
			"cd " + CONTAINER_ROOT
				+ " && PGHOST=127.0.0.1"
				+ " PGPORT=5432"
				+ " PGDATABASE=" + DATABASE
				+ " PGUSER=" + CanonicalPostgresContainer.username()
				+ " PGPASSFILE=" + CONTAINER_ROOT + "/.pgpass"
				+ " ./deploy/scripts/apply-admin-dashboard-migrations.sh"
		);
	}

	private void assertSuccessful(Container.ExecResult result) {
		assertThat(result.getExitCode())
			.withFailMessage("stdout:%n%s%nstderr:%n%s", result.getStdout(), result.getStderr())
			.isZero();
		assertThat(result.getStdout()).contains("Admin dashboard schema verification passed.");
	}

	private void assertMismatch(Container.ExecResult result, String message) {
		assertThat(result.getExitCode()).isNotZero();
		assertThat(result.getStderr()).contains(message);
	}

	private long authVersionColumnCount() {
		return jdbc.sql("""
			SELECT count(*)
			FROM pg_attribute
			WHERE attrelid = 'public.users'::regclass
			  AND attname = 'auth_version'
			  AND attnum > 0
			  AND NOT attisdropped
			""").query(Long.class).single();
	}

	private void lockAdvisory(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("SELECT pg_advisory_lock(hashtextextended('" + ADVISORY_LOCK_NAME + "', 0))");
		}
	}

	private boolean tryAdvisoryLock(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
			 ResultSet result = statement.executeQuery(
				 "SELECT pg_try_advisory_lock(hashtextextended('" + ADVISORY_LOCK_NAME + "', 0))"
			 )) {
			result.next();
			return result.getBoolean(1);
		}
	}

	private void unlockAdvisoryLock(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("SELECT pg_advisory_unlock(hashtextextended('" + ADVISORY_LOCK_NAME + "', 0))");
		}
	}

	private void awaitBlockedAdvisoryLock() {
		long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
		while (System.nanoTime() < deadline) {
			Long waiting = jdbc.sql("""
				SELECT count(*)
				FROM pg_locks lock_row
				JOIN pg_stat_activity activity ON activity.pid = lock_row.pid
				WHERE lock_row.locktype = 'advisory'
				  AND NOT lock_row.granted
				  AND activity.datname = current_database()
				  AND activity.wait_event_type = 'Lock'
				  AND activity.wait_event = 'advisory'
				  AND activity.query LIKE '%pg_advisory_lock%ieum:admin-dashboard:v25-v26%'
				""").query(Long.class).single();
			if (waiting != null && waiting == 1L) {
				return;
			}
			pause();
		}
		throw new AssertionError("migration helper did not block on the held advisory lock");
	}

	private void pause() {
		try {
			Thread.sleep(20);
		}
		catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while waiting for advisory lock contention", exception);
		}
	}

	private void createAuditSchemaWithGeneratedTargetId() {
		jdbc.sql("""
			CREATE TABLE admin_audit_logs (
				audit_id BIGSERIAL PRIMARY KEY,
				actor_user_id BIGINT REFERENCES users(user_id) ON DELETE SET NULL,
				action TEXT NOT NULL,
				target_type TEXT NOT NULL,
				target_id BIGINT GENERATED ALWAYS AS (COALESCE(actor_user_id, 0)) STORED NOT NULL,
				details JSONB NOT NULL,
				created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
				CONSTRAINT ck_admin_audit_logs_action CHECK (
					action IN (
						'USER_SANCTION_CREATED',
						'USER_ACTIVATED',
						'USER_ROLE_CHANGED',
						'REPORT_CONFIRMED',
						'REPORT_DISMISSED',
						'INQUIRY_ANSWERED'
					)
				),
				CONSTRAINT ck_admin_audit_logs_target_type CHECK (
					target_type IN ('user', 'report', 'inquiry')
				),
				CONSTRAINT ck_admin_audit_logs_details_object CHECK (
					jsonb_typeof(details) = 'object'
				)
			)
			""").update();
		jdbc.sql("""
			CREATE INDEX idx_admin_audit_logs_actor_created
			ON admin_audit_logs(actor_user_id, created_at DESC, audit_id DESC)
			""").update();
		jdbc.sql("""
			CREATE INDEX idx_admin_audit_logs_target_created
			ON admin_audit_logs(target_type, target_id, created_at DESC, audit_id DESC)
			""").update();
		jdbc.sql("""
			CREATE INDEX idx_admin_audit_logs_created_desc
			ON admin_audit_logs(created_at DESC, audit_id DESC)
			""").update();
	}

	private void copyMigrationFiles() throws Exception {
		Container.ExecResult mkdir = CanonicalPostgresContainer.instance().execInContainer(
			"mkdir",
			"-p",
			CONTAINER_ROOT + "/deploy/scripts",
			CONTAINER_ROOT + "/db/migrations"
		);
		assertThat(mkdir.getExitCode()).isZero();

		copyToContainer(
			"deploy/scripts/apply-admin-dashboard-migrations.sh",
			CONTAINER_ROOT + "/deploy/scripts/apply-admin-dashboard-migrations.sh",
			0755
		);
		copyToContainer(
			"db/migrations/v25_user_auth_version.sql",
			CONTAINER_ROOT + "/db/migrations/v25_user_auth_version.sql",
			0644
		);
		copyToContainer(
			"db/migrations/v25_web_push_subscriptions.sql",
			CONTAINER_ROOT + "/db/migrations/v25_web_push_subscriptions.sql",
			0644
		);
		copyToContainer(
			"db/migrations/v26_admin_audit_logs.sql",
			CONTAINER_ROOT + "/db/migrations/v26_admin_audit_logs.sql",
			0644
		);
		copyToContainer(
			"db/migrations/v26_web_push_session_cardinality.sql",
			CONTAINER_ROOT + "/db/migrations/v26_web_push_session_cardinality.sql",
			0644
		);
		copyToContainer(
			"db/migrations/v28_chat_system_messages.sql",
			CONTAINER_ROOT + "/db/migrations/v28_chat_system_messages.sql",
			0644
		);
		copyToContainer(
			"db/migrations/v29_meeting_schedule_details.sql",
			CONTAINER_ROOT + "/db/migrations/v29_meeting_schedule_details.sql",
			0644
		);
		copyToContainer(
			"db/migrations/v30_report_schedule_target_enum.sql",
			CONTAINER_ROOT + "/db/migrations/v30_report_schedule_target_enum.sql",
			0644
		);
		copyToContainer(
			"db/migrations/v31_report_schedule_target.sql",
			CONTAINER_ROOT + "/db/migrations/v31_report_schedule_target.sql",
			0644
		);
		String pgPass = "127.0.0.1:5432:%s:%s:%s%n".formatted(
			DATABASE,
			pgPassEscape(CanonicalPostgresContainer.username()),
			pgPassEscape(CanonicalPostgresContainer.password())
		);
		copyContentToContainer(pgPass, CONTAINER_ROOT + "/.pgpass", 0600);
	}

	private void copyToContainer(String relativePath, String containerPath, int mode) {
		Path source = repositoryRoot().resolve(relativePath);
		try {
			CanonicalPostgresContainer.instance().copyFileToContainer(
				Transferable.of(Files.readAllBytes(source), mode),
				containerPath
			);
		}
		catch (IOException exception) {
			throw new UncheckedIOException("Failed to read " + source, exception);
		}
	}

	private void copyContentToContainer(String content, String containerPath, int mode) {
		CanonicalPostgresContainer.instance().copyFileToContainer(
			Transferable.of(content.getBytes(StandardCharsets.UTF_8), mode),
			containerPath
		);
	}

	private String pgPassEscape(String value) {
		return value.replace("\\", "\\\\").replace(":", "\\:");
	}

	private Path repositoryRoot() {
		Path current = Path.of("").toAbsolutePath().normalize();
		while (current != null) {
			if (Files.isRegularFile(current.resolve("settings.gradle.kts"))
				&& Files.isDirectory(current.resolve("deploy"))) {
				return current;
			}
			current = current.getParent();
		}
		throw new IllegalStateException("Repository root not found");
	}
}
