package shinhan.fibri.ieum.ai.question.webgrounding;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublicWebSourceUriValidatorTest {

	private final PublicWebSourceUriValidator validator = new PublicWebSourceUriValidator();

	@ParameterizedTest
	@ValueSource(strings = {
		"https://example.com/path?q=grounding",
		"http://example.com:80/source",
		"https://example.com:443/source",
		"https://1.1.1.1/source",
		"https://[2606:4700:4700::1111]/source",
		"https://[2001:4860:4860::8888]/source"
	})
	void acceptsAbsolutePublicHttpSources(String rawUri) {
		assertThat(validator.validate(rawUri)).contains(URI.create(rawUri));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"ftp://example.com/source",
		"https://user:password@example.com/source",
		"https://example.com:8443/source",
		"https://0x7f000001/source",
		"https://2130706433/source",
		"https://router/source",
		"/relative/source",
		"https://",
		"https://exa mple.com/source"
	})
	void rejectsUnsupportedOrMalformedUris(String rawUri) {
		assertThat(validator.validate(rawUri)).isEmpty();
	}

	@Test
	void rejectsMissingAndOversizedUrisWithoutThrowing() {
		assertThat(validator.validate(null)).isEmpty();
		assertThat(validator.validate("  ")).isEmpty();
		assertThat(validator.validate("https://example.com/" + "a".repeat(2049))).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"https://localhost/source",
		"https://api.localhost/source",
		"https://printer.local/source",
		"https://service.internal/source",
		"https://LOCALHOST./source"
	})
	void rejectsLocalHostnames(String rawUri) {
		assertThat(validator.validate(rawUri)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"https://0.0.0.0/source",
		"https://10.0.0.1/source",
		"https://100.64.0.1/source",
		"https://100.127.255.254/source",
		"https://127.0.0.1/source",
		"https://169.254.1.1/source",
		"https://172.16.0.1/source",
		"https://172.31.255.254/source",
		"https://192.0.2.1/source",
		"https://192.88.99.1/source",
		"https://192.168.1.1/source",
		"https://198.18.0.1/source",
		"https://198.51.100.1/source",
		"https://203.0.113.1/source",
		"https://224.0.0.1/source",
		"https://240.0.0.1/source",
		"https://255.255.255.255/source"
	})
	void rejectsNonPublicIpv4Literals(String rawUri) {
		assertThat(validator.validate(rawUri)).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"https://[::]/source",
		"https://[::1]/source",
		"https://[fe80::1]/source",
		"https://[fec0::1]/source",
		"https://[fc00::1]/source",
		"https://[fd00::1]/source",
		"https://[ff02::1]/source",
		"https://[2001::1]/source",
		"https://[2001:2::1]/source",
		"https://[2001:10::1]/source",
		"https://[2001:20::1]/source",
		"https://[2001:db8::1]/source",
		"https://[2002:a00::1]/source",
		"https://[3fff::1]/source",
		"https://[::ffff:127.0.0.1]/source"
	})
	void rejectsNonPublicIpv6Literals(String rawUri) {
		assertThat(validator.validate(rawUri)).isEmpty();
	}

	@Test
	void validatesHostnamesWithoutResolvingThem() {
		Stream.of(
			"https://grounding-source.example.com/article",
			"https://xn--3e0b707e.example.com/article"
		).forEach(rawUri -> assertThat(validator.validate(rawUri)).contains(URI.create(rawUri)));
	}
}
