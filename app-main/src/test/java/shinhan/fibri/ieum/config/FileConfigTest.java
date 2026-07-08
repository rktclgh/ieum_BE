package shinhan.fibri.ieum.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class FileConfigTest {

	@Test
	void s3ClientOverrideConfigurationUsesConfiguredTimeouts() {
		var configuration = FileConfig.s3ClientOverrideConfiguration(10L, 3L);

		assertThat(configuration.apiCallTimeout()).contains(Duration.ofSeconds(10L));
		assertThat(configuration.apiCallAttemptTimeout()).contains(Duration.ofSeconds(3L));
	}
}
