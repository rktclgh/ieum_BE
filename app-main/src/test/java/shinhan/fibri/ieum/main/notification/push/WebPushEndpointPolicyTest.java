package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebPushEndpointPolicyTest {

	private final WebPushEndpointPolicy policy = new WebPushEndpointPolicy(Set.of("push.example.test"));

	@ParameterizedTest
	@ValueSource(strings = {
		"https://push.example.test/message/123",
		"https://updates.push.example.test/message/123"
	})
	void acceptsExactAndDotBoundarySubdomain(String endpoint) {
		assertThat(policy.validate(endpoint).toString()).isEqualTo(endpoint);
	}

	@Test
	void acceptsEndpointAtMaximumLength() {
		String prefix = "https://push.example.test/";
		String endpoint = prefix + "a".repeat(2_048 - prefix.length());

		assertThat(endpoint).hasSize(2_048);
		assertThat(policy.validate(endpoint).toString()).isEqualTo(endpoint);
	}

	@Test
	void acceptsAppleWebPushSubdomainAndRejectsLookalikeHost() {
		WebPushEndpointPolicy applePolicy = new WebPushEndpointPolicy(Set.of("push.apple.com"));

		assertThat(applePolicy.validate("https://web.push.apple.com/message/123").toString())
			.isEqualTo("https://web.push.apple.com/message/123");
		assertThatThrownBy(() -> applePolicy.validate("https://notpush.apple.com/message/123"))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"https://push.example.test.evil.example/message/123",
		"https://notpush.example.test/message/123"
	})
	void rejectsLookalikeHostsWithoutLeakingEndpoint(String endpoint) {
		assertThatThrownBy(() -> policy.validate(endpoint))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining(endpoint)
			.hasMessageNotContaining("message/123");
	}

	@ParameterizedTest
	@ValueSource(strings = {
		" http://push.example.test/message/123",
		"https://user@push.example.test/message/123",
		"https://push.example.test:8443/message/123",
		"https://push.example.test/message/123#fragment"
	})
	void rejectsUnsafeEndpointShapes(String endpoint) {
		assertThatThrownBy(() -> policy.validate(endpoint))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining(endpoint);
	}

	@ParameterizedTest
	@ValueSource(ints = {2049, 4096})
	void rejectsEndpointsLongerThan2048Characters(int pathLength) {
		String endpoint = "https://push.example.test/" + "a".repeat(pathLength);

		assertThatThrownBy(() -> policy.validate(endpoint))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageNotContaining(endpoint);
	}
}
