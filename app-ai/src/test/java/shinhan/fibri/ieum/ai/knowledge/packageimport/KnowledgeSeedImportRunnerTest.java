package shinhan.fibri.ieum.ai.knowledge.packageimport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.core.io.AbstractResource;
import org.springframework.core.io.Resource;
import shinhan.fibri.ieum.ai.config.AiDatabaseCapabilityVerifier;
import shinhan.fibri.ieum.ai.config.AiDatabaseProperties;

class KnowledgeSeedImportRunnerTest {

	@ParameterizedTest
	@MethodSource("successfulOutcomes")
	void importsOnceAndClosesInputForEverySuccessfulOutcome(KnowledgeSeedImportOutcome outcome) throws Exception {
		KnowledgeSeedPackageImporter importer = mock(KnowledgeSeedPackageImporter.class);
		TrackingResource resource = new TrackingResource("{}");
		when(importer.importPackage(any(InputStream.class))).thenReturn(outcome);
		KnowledgeSeedImportRunner runner = new KnowledgeSeedImportRunner(importer, resource);

		runner.run(mock(ApplicationArguments.class));

		verify(importer).importPackage(resource.inputStream());
		assertThat(resource.inputStream().closed()).isTrue();
		assertThat(runner.getOrder()).isEqualTo(100);
	}

	@Test
	void propagatesImporterFailureAndClosesInput() {
		KnowledgeSeedPackageImporter importer = mock(KnowledgeSeedPackageImporter.class);
		TrackingResource resource = new TrackingResource("{}");
		RuntimeException failure = new IllegalStateException("embedding failed");
		when(importer.importPackage(any(InputStream.class))).thenThrow(failure);
		KnowledgeSeedImportRunner runner = new KnowledgeSeedImportRunner(importer, resource);

		assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
			.isSameAs(failure);
		assertThat(resource.inputStream().closed()).isTrue();
	}

	@Test
	void propagatesResourceFailureWithoutInvokingImporter() {
		KnowledgeSeedPackageImporter importer = mock(KnowledgeSeedPackageImporter.class);
		Resource missing = new AbstractResource() {
			@Override
			public String getDescription() {
				return "missing knowledge package";
			}

			@Override
			public InputStream getInputStream() throws IOException {
				throw new FileNotFoundException("missing knowledge package");
			}
		};
		KnowledgeSeedImportRunner runner = new KnowledgeSeedImportRunner(importer, missing);

		assertThatThrownBy(() -> runner.run(mock(ApplicationArguments.class)))
			.isInstanceOf(FileNotFoundException.class)
			.hasMessageContaining("missing knowledge package");
		verify(importer, never()).importPackage(any(InputStream.class));
	}

	@Test
	void runsAfterDatabaseCapabilityVerification() {
		AiDatabaseCapabilityVerifier verifier = new AiDatabaseCapabilityVerifier(
			null,
			new AiDatabaseProperties(768, Set.of("vector", "postgis", "pgcrypto")),
			null
		);
		KnowledgeSeedImportRunner runner = new KnowledgeSeedImportRunner(
			mock(KnowledgeSeedPackageImporter.class),
			new TrackingResource("{}")
		);

		assertThat(verifier.getOrder()).isLessThan(runner.getOrder());
	}

	private static Stream<KnowledgeSeedImportOutcome> successfulOutcomes() {
		return Stream.of(KnowledgeSeedImportOutcome.IMPORTED, KnowledgeSeedImportOutcome.NO_OP);
	}

	private static final class TrackingResource extends AbstractResource {

		private final TrackingInputStream inputStream;

		private TrackingResource(String content) {
			this.inputStream = new TrackingInputStream(content.getBytes(StandardCharsets.UTF_8));
		}

		@Override
		public String getDescription() {
			return "tracking knowledge package";
		}

		@Override
		public InputStream getInputStream() {
			return inputStream;
		}

		private TrackingInputStream inputStream() {
			return inputStream;
		}
	}

	private static final class TrackingInputStream extends ByteArrayInputStream {

		private boolean closed;

		private TrackingInputStream(byte[] content) {
			super(content);
		}

		@Override
		public void close() throws IOException {
			closed = true;
			super.close();
		}

		private boolean closed() {
			return closed;
		}
	}
}
