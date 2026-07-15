package shinhan.fibri.ieum.main.notification.push;

public record WebPushConfigResponse(
	boolean enabled,
	String vapidPublicKey,
	boolean subscribed
) {
}
