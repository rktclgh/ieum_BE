package shinhan.fibri.ieum.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.atomic.AtomicLong;
import org.testcontainers.containers.Container;
import org.testcontainers.images.builder.Transferable;

public final class SqlScriptRunner {
	private static final String RESOURCE_ROOT = "canonical-db/";
	private static final AtomicLong SEQUENCE = new AtomicLong();

	private SqlScriptRunner() {
	}

	public static void run(String databaseName, String... classpathResources) {
		CanonicalPostgresContainer.validateDatabaseName(databaseName);
		for (String classpathResource : classpathResources) {
			String containerPath = "/tmp/ieum-sql-" + SEQUENCE.incrementAndGet() + ".sql";
			CanonicalPostgresContainer.instance().copyFileToContainer(
				Transferable.of(readClasspathBytes(classpathResource), 0644), containerPath);

			try {
				Container.ExecResult result = CanonicalPostgresContainer.instance().execInContainer(
					"psql", "-v", "ON_ERROR_STOP=1", "-U", CanonicalPostgresContainer.username(),
					"-d", databaseName, "-f", containerPath);
				if (result.getExitCode() != 0) {
					throw new IllegalStateException("SQL script failed: " + classpathResource + "\n" + result.getStderr());
				}
			}
			catch (IOException exception) {
				throw new UncheckedIOException("Failed to execute " + classpathResource, exception);
			}
			catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Interrupted while executing " + classpathResource, exception);
			}
		}
	}

	private static byte[] readClasspathBytes(String resourceName) {
		String namespacedResource = RESOURCE_ROOT + resourceName;
		try (var input = SqlScriptRunner.class.getClassLoader().getResourceAsStream(namespacedResource)) {
			if (input == null) {
				throw new IllegalArgumentException(
					"Classpath SQL resource not found: " + namespacedResource
				);
			}
			return input.readAllBytes();
		}
		catch (IOException exception) {
			throw new UncheckedIOException(
				"Failed to read classpath SQL resource: " + namespacedResource,
				exception
			);
		}
	}
}
