package shinhan.fibri.ieum.main.report.ai.service;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class ReportAiWorkerPropertiesTest {

	@Test
	void rejectsInvalidWorkerSettings() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ReportAiWorkerProperties(" ", Duration.ofSeconds(1), 1, 1));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ReportAiWorkerProperties("worker", Duration.ZERO, 1, 1));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ReportAiWorkerProperties("worker", Duration.ofSeconds(1), 6, 1));
		assertThatIllegalArgumentException()
			.isThrownBy(() -> new ReportAiWorkerProperties("worker", Duration.ofSeconds(1), 1, 33));
	}
}
