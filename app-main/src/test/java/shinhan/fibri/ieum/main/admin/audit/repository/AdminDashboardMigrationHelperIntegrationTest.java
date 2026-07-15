package shinhan.fibri.ieum.main.admin.audit.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

	private JdbcClient jdbc;

	@BeforeEach
	void recreateDatabaseAndCopyScripts() throws Exception {
		CanonicalPostgresContainer.recreateDatabase(DATABASE);
		jdbc = JdbcClient.create(CanonicalPostgresContainer.dataSource(DATABASE));
		jdbc.sql("CREATE TABLE users (user_id BIGSERIAL PRIMARY KEY)").update();
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
		jdbc.sql("CREATE TABLE admin_audit_logs (audit_id BIGSERIAL PRIMARY KEY)").update();

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
	void concurrentRunsSerializeOnTheSessionAdvisoryLock() throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			List<Future<Container.ExecResult>> runs = List.of(
				executor.submit(() -> runAfter(start)),
				executor.submit(() -> runAfter(start))
			);
			start.countDown();

			for (Future<Container.ExecResult> run : runs) {
				assertSuccessful(run.get());
			}
		}

		assertThat(jdbc.sql("SELECT to_regclass('public.admin_audit_logs') IS NOT NULL")
			.query(Boolean.class).single()).isTrue();
	}

	private Container.ExecResult runAfter(CountDownLatch start) throws Exception {
		start.await();
		return runHelper();
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
				+ " PGPASSWORD=" + CanonicalPostgresContainer.password()
				+ " ./deploy/scripts/apply-admin-dashboard-migrations.sh"
		);
	}

	private void assertSuccessful(Container.ExecResult result) {
		assertThat(result.getExitCode())
			.withFailMessage("stdout:%n%s%nstderr:%n%s", result.getStdout(), result.getStderr())
			.isZero();
		assertThat(result.getStdout()).contains("Admin dashboard schema verification passed.");
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
			"db/migrations/v26_admin_audit_logs.sql",
			CONTAINER_ROOT + "/db/migrations/v26_admin_audit_logs.sql",
			0644
		);
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
