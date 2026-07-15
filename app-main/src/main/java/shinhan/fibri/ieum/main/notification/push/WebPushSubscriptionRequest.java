package shinhan.fibri.ieum.main.notification.push;

public record WebPushSubscriptionRequest(
	String endpoint,
	Long expirationTime,
	Keys keys
) {

	@Override
	public String toString() {
		return "WebPushSubscriptionRequest[endpoint=<redacted>, expirationTime=%s, keys=<redacted>]"
			.formatted(expirationTime);
	}

	public record Keys(String p256dh, String auth) {

		@Override
		public String toString() {
			return "Keys[p256dh=<redacted>, auth=<redacted>]";
		}
	}
}
