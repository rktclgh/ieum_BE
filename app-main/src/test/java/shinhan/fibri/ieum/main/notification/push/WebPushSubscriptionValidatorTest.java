package shinhan.fibri.ieum.main.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WebPushSubscriptionValidatorTest {

	private static final Instant NOW = Instant.parse("2026-07-15T00:00:00Z");
	private static final long MAX_EXPIRATION_EPOCH_MILLIS = 253_402_300_799_999L;
	private WebPushSubscriptionValidator validator;

	@BeforeEach
	void setUp() {
		validator = new WebPushSubscriptionValidator(
			Set.of("fcm.googleapis.com", "push.services.mozilla.com"),
			Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"https://fcm.googleapis.com/push/123",
		"https://updates.fcm.googleapis.com/push/123"
	})
	void acceptsExactAndSubdomainAllowedEndpoints(String endpoint) {
		WebPushSubscriptionRequest request = validRequest(endpoint, NOW.plusSeconds(60).toEpochMilli());

		WebPushSubscriptionInput result = validator.validate(42L, "session-42", request);

		assertThat(result.userId()).isEqualTo(42L);
		assertThat(result.sessionId()).isEqualTo("session-42");
		assertThat(result.endpoint()).isEqualTo(endpoint);
		assertThat(result.expiresAt()).isEqualTo(OffsetDateTime.parse("2026-07-15T00:01:00Z"));
	}

	@ParameterizedTest
	@ValueSource(strings = {
		"http://fcm.googleapis.com/push/123",
		" https://fcm.googleapis.com/push/123",
		"https://user@fcm.googleapis.com/push/123",
		"https://fcm.googleapis.com/push/123#fragment",
		"https://fcm.googleapis.com:8443/push/123",
		"https://fcm.googleapis.com.evil.example/push/123",
		"https://notfcm.googleapis.com/push/123"
	})
	void rejectsUnsafeOrUnlistedEndpoints(String endpoint) {
		assertThatThrownBy(() -> validator.validate(42L, "session-42", validRequest(endpoint, null)))
			.isInstanceOf(InvalidWebPushSubscriptionException.class)
			.satisfies(exception -> assertThat(((InvalidWebPushSubscriptionException) exception).field())
				.isEqualTo("endpoint"));
	}

	@Test
	void rejectsInvalidP256dh() {
		WebPushSubscriptionRequest request = new WebPushSubscriptionRequest(
			"https://fcm.googleapis.com/push/123",
			null,
			new WebPushSubscriptionRequest.Keys(base64Url(new byte[64]), validAuth())
		);

		assertThatThrownBy(() -> validator.validate(42L, "session-42", request))
			.isInstanceOf(InvalidWebPushSubscriptionException.class)
			.satisfies(exception -> assertThat(((InvalidWebPushSubscriptionException) exception).field())
				.isEqualTo("keys.p256dh"));
	}

	@Test
	void rejectsP256dhWithWrongUncompressedPointPrefix() {
		byte[] key = new byte[65];
		key[0] = 0x03;
		WebPushSubscriptionRequest request = new WebPushSubscriptionRequest(
			"https://fcm.googleapis.com/push/123",
			null,
			new WebPushSubscriptionRequest.Keys(base64Url(key), validAuth())
		);

		assertThatThrownBy(() -> validator.validate(42L, "session-42", request))
			.isInstanceOf(InvalidWebPushSubscriptionException.class)
			.satisfies(exception -> assertThat(((InvalidWebPushSubscriptionException) exception).field())
				.isEqualTo("keys.p256dh"));
	}

	@Test
	void rejectsMalformedBase64UrlKey() {
		WebPushSubscriptionRequest request = new WebPushSubscriptionRequest(
			"https://fcm.googleapis.com/push/123",
			null,
			new WebPushSubscriptionRequest.Keys("not+base64url", validAuth())
		);

		assertThatThrownBy(() -> validator.validate(42L, "session-42", request))
			.isInstanceOf(InvalidWebPushSubscriptionException.class)
			.satisfies(exception -> assertThat(((InvalidWebPushSubscriptionException) exception).field())
				.isEqualTo("keys.p256dh"));
	}

	@Test
	void rejectsOversizedKeysBeforeDecoding() {
		WebPushSubscriptionRequest oversizedP256dh = new WebPushSubscriptionRequest(
			"https://fcm.googleapis.com/push/123",
			null,
			new WebPushSubscriptionRequest.Keys("A".repeat(513), validAuth())
		);
		WebPushSubscriptionRequest oversizedAuth = new WebPushSubscriptionRequest(
			"https://fcm.googleapis.com/push/123",
			null,
			new WebPushSubscriptionRequest.Keys(validP256dh(), "A".repeat(257))
		);

		assertThatThrownBy(() -> validator.validate(42L, "session-42", oversizedP256dh))
			.isInstanceOf(InvalidWebPushSubscriptionException.class)
			.hasMessage("Push encryption key is too long");
		assertThatThrownBy(() -> validator.validate(42L, "session-42", oversizedAuth))
			.isInstanceOf(InvalidWebPushSubscriptionException.class)
			.hasMessage("Push encryption key is too long");
	}

	@Test
	void rejectsInvalidAuthSecret() {
		WebPushSubscriptionRequest request = new WebPushSubscriptionRequest(
			"https://fcm.googleapis.com/push/123",
			null,
			new WebPushSubscriptionRequest.Keys(validP256dh(), base64Url(new byte[15]))
		);

		assertThatThrownBy(() -> validator.validate(42L, "session-42", request))
			.isInstanceOf(InvalidWebPushSubscriptionException.class)
			.satisfies(exception -> assertThat(((InvalidWebPushSubscriptionException) exception).field())
				.isEqualTo("keys.auth"));
	}

	@ParameterizedTest
	@ValueSource(longs = {1784073599999L, 1784073600000L})
	void rejectsPastAndCurrentExpiration(long expirationTime) {
		assertThatThrownBy(() -> validator.validate(
			42L,
			"session-42",
			validRequest("https://fcm.googleapis.com/push/123", expirationTime)
		))
			.isInstanceOf(InvalidWebPushSubscriptionException.class)
			.satisfies(exception -> assertThat(((InvalidWebPushSubscriptionException) exception).field())
				.isEqualTo("expirationTime"));
	}

	@ParameterizedTest
	@ValueSource(longs = {253_402_300_800_000L, Long.MAX_VALUE})
	void rejectsExpirationBeyondDatabaseSafeMaximum(long expirationTime) {
		assertThatThrownBy(() -> validator.validate(
			42L,
			"session-42",
			validRequest("https://fcm.googleapis.com/push/123", expirationTime)
		))
			.isInstanceOf(InvalidWebPushSubscriptionException.class)
			.satisfies(exception -> assertThat(((InvalidWebPushSubscriptionException) exception).field())
				.isEqualTo("expirationTime"));
	}

	@Test
	void acceptsDatabaseSafeMaximumExpiration() {
		WebPushSubscriptionInput result = validator.validate(
			42L,
			"session-42",
			validRequest("https://fcm.googleapis.com/push/123", MAX_EXPIRATION_EPOCH_MILLIS)
		);

		assertThat(result.expiresAt()).isEqualTo(OffsetDateTime.parse("9999-12-31T23:59:59.999Z"));
	}

	@Test
	void requestDiagnosticsRedactEndpointAndKeys() {
		String endpoint = "https://fcm.googleapis.com/private-endpoint";
		String p256dh = validP256dh();
		String auth = validAuth();
		WebPushSubscriptionRequest request = new WebPushSubscriptionRequest(
			endpoint,
			null,
			new WebPushSubscriptionRequest.Keys(p256dh, auth)
		);

		assertThat(request.toString())
			.doesNotContain(endpoint, p256dh, auth)
			.contains("redacted");
		assertThat(request.keys().toString())
			.doesNotContain(p256dh, auth)
			.contains("redacted");
	}

	private static WebPushSubscriptionRequest validRequest(String endpoint, Long expirationTime) {
		return new WebPushSubscriptionRequest(
			endpoint,
			expirationTime,
			new WebPushSubscriptionRequest.Keys(validP256dh(), validAuth())
		);
	}

	private static String validP256dh() {
		byte[] bytes = new byte[65];
		bytes[0] = 0x04;
		return base64Url(bytes);
	}

	private static String validAuth() {
		return base64Url(new byte[16]);
	}

	private static String base64Url(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}
}
