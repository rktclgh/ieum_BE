package shinhan.fibri.ieum.ai.report.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ReportEvidenceImageUrlValidatorTest {

	private final ReportEvidenceImageUrlValidator validator = new ReportEvidenceImageUrlValidator(
		Set.of("ieum-files.s3.ap-northeast-2.amazonaws.com")
	);

	@Test
	void acceptsAnHttpsPresignedUrlForAnExactAllowedHost() {
		URI uri = validator.validate(
			"https://ieum-files.s3.ap-northeast-2.amazonaws.com/final/42/chat/id/display.webp?X-Amz-Signature=secret"
		);

		assertThat(uri.getHost()).isEqualTo("ieum-files.s3.ap-northeast-2.amazonaws.com");
		assertThat(uri.getRawQuery()).isEqualTo("X-Amz-Signature=secret");
	}

	@Test
	void rejectsUrlsOutsideTheConfiguredS3HostAllowlist() {
		assertThatThrownBy(() -> validator.validate("https://other-files.s3.ap-northeast-2.amazonaws.com/image.webp"))
			.isInstanceOf(InvalidReportReviewRequestException.class)
			.hasMessageContaining("host");
	}

	@Test
	void rejectsAHostThatOnlyLooksLikeTheAllowedHost() {
		assertThatThrownBy(() -> validator.validate("https://ieum-files.s3.ap-northeast-2.amazonaws.com.attacker.test/image.webp"))
			.isInstanceOf(InvalidReportReviewRequestException.class)
			.hasMessageContaining("host");
	}

	@Test
	void rejectsNonHttpsUserInfoExplicitPortsAndIpLiterals() {
		assertRejected("http://ieum-files.s3.ap-northeast-2.amazonaws.com/image.webp");
		assertRejected("https://user@ieum-files.s3.ap-northeast-2.amazonaws.com/image.webp");
		assertRejected("https://ieum-files.s3.ap-northeast-2.amazonaws.com:443/image.webp");
		assertRejected("https://127.0.0.1/image.webp");
		assertRejected("https://[::1]/image.webp");
	}

	@Test
	void rejectsMalformedUrlsAndFragments() {
		assertRejected("not a url");
		assertRejected("https://ieum-files.s3.ap-northeast-2.amazonaws.com/image.webp#fragment");
	}

	private void assertRejected(String value) {
		assertThatThrownBy(() -> validator.validate(value))
			.isInstanceOf(InvalidReportReviewRequestException.class);
	}
}
