package shinhan.fibri.ieum.ai.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 봉투(envelope) 수준 {@code schemaVersion} 검증. spec.md §6.5 "버전 관리 규칙":
 * schemaVersion 이 없거나 지원 범위(SUPPORTED_MAX)를 넘으면 재시도 없이 즉시 DLQ.
 */
class AiJobMessageSettlementTest {

	@Test
	void missingSchemaVersionIsDlq() {
		Optional<String> violation = AiJobMessageSettlement.validateSchemaVersion(null);

		assertThat(violation).contains(AiJobMessageSettlement.REASON_MISSING_SCHEMA_VERSION);
	}

	@Test
	void schemaVersionAboveSupportedMaxIsDlq() {
		Optional<String> violation = AiJobMessageSettlement.validateSchemaVersion(
			AiJobMessageSettlement.SUPPORTED_MAX_SCHEMA_VERSION + 1
		);

		assertThat(violation).contains(AiJobMessageSettlement.REASON_UNSUPPORTED_SCHEMA_VERSION);
	}

	@Test
	void supportedSchemaVersionPassesValidation() {
		Optional<String> violation = AiJobMessageSettlement.validateSchemaVersion(
			AiJobMessageSettlement.SUPPORTED_MAX_SCHEMA_VERSION
		);

		assertThat(violation).isEmpty();
	}

	@Test
	void settlementEnumHasExactlyAckRetryAndDlq() {
		assertThat(AiJobMessageSettlement.values()).containsExactly(
			AiJobMessageSettlement.ACK,
			AiJobMessageSettlement.RETRY,
			AiJobMessageSettlement.DLQ
		);
	}
}
